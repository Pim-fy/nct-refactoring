package negocut.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionResult;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.AuctionResultRepository;
import negocut.auction.repository.BidRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auction.service.AuctionCloseProcessor;
import negocut.auction.service.AuctionClosingService;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.entity.PointHistory;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeRepository;

// 경매 종료·낙찰 (기능 명세서 3-4 경매 종료 처리, 3-7 낙찰)
// 스케줄러는 테스트에서 꺼 두고(build.gradle), 종료 처리 서비스를 직접 불러 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
class AuctionClosingTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private AuctionResultRepository auctionResultRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private AuctionCloseProcessor processor;
    @Autowired private AuctionClosingService closingService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member newMember() {
        String suffix = TestSuffix.next();
        Member member = Member.create("cl" + suffix, passwordEncoder.encode("abcd1234"),
                "낙찰" + suffix.substring(2), "cl" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", MemberRole.MEMBER);
        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.NORMAL);
        member = memberRepository.save(member);
        PointBalance balance = pointBalanceRepository.save(PointBalance.createEmpty(member));
        ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(10_000_000));
        pointBalanceRepository.save(balance);
        return member;
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Auction newAuction(Member seller) {
        Category category = categoryRepository.save(Category.create("종료" + TestSuffix.next(), 8500));
        Product product = productRepository.save(Product.create(category, "종료 상품 " + TestSuffix.next(), "설명", "상태 설명"));
        return auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusMinutes(120)));
    }

    private void bid(Member bidder, Auction auction, long price) throws Exception {
        mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":" + price + "}").cookie(cookieOf(bidder))).andExpect(status().isOk());
    }

    // 종료 시각을 지난 시각으로 돌린다.
    private void expire(Auction auction) {
        Auction saved = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "endAt", LocalDateTime.now(ZONE).minusSeconds(5));
        auctionRepository.save(saved);
    }

    private Auction reload(Auction auction) {
        return auctionRepository.findById(auction.getId()).orElseThrow();
    }

    private long resultCount(Auction auction) {
        return entityManager.createQuery("select count(r) from AuctionResult r where r.auction.id = :id", Long.class)
                .setParameter("id", auction.getId()).getSingleResult();
    }

    private long tradeCount(Auction auction) {
        return entityManager.createQuery("select count(t) from Trade t where t.auctionResult.auction.id = :id", Long.class)
                .setParameter("id", auction.getId()).getSingleResult();
    }

    // ---- 낙찰 ----

    @Test
    void 종료_시각이_지난_경매는_최고_입찰로_낙찰되고_낙찰_정보와_거래가_한_건씩_만들어진다() throws Exception {
        Member seller = newMember();
        Member first = newMember();
        Member winner = newMember();
        Auction auction = newAuction(seller);
        bid(first, auction, 300_000);
        bid(winner, auction, 310_000);
        Long topBidId = reload(auction).getTopBidId();
        long historyBefore = pointHistoryRepository.count();
        expire(auction);

        assertThat(processor.close(auction.getId())).isTrue();

        Auction closed = reload(auction);
        assertThat(closed.getAuctionStatus()).isEqualTo(AuctionStatus.SOLD);
        assertThat(closed.getClosedAt()).isNotNull();
        AuctionResult result = auctionResultRepository.findByAuctionId(auction.getId()).orElseThrow();
        assertThat(result.getWinner().getId()).isEqualTo(winner.getId());
        assertThat(result.getWinningBid().getId()).isEqualTo(topBidId);
        assertThat(result.getWinningPrice()).isEqualByComparingTo("310000");
        assertThat(result.getConfirmedAt()).isEqualTo(closed.getClosedAt());
        List<Trade> trades = tradeRepository.findAll().stream().filter(t -> t.getAuctionResult().getId().equals(result.getId())).toList();
        assertThat(trades).hasSize(1);
        Trade trade = trades.get(0);
        assertThat(trade.getSeller().getId()).isEqualTo(seller.getId());
        assertThat(trade.getBuyer().getId()).isEqualTo(winner.getId());
        assertThat(trade.getTradePrice()).isEqualByComparingTo("310000");
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.READY);
        assertThat(trade.getShipDueAt()).isEqualTo(closed.getClosedAt().plusDays(5));   // 낙찰 후 5일
        assertThat(trade.getConfirmDueAt()).isNull();
        assertThat(trade.isAutoConfirmed()).isFalse();
        assertThat(trade.isUnfulfilled()).isFalse();
        // 낙찰자의 예약은 유지되고, 포인트 이력은 더 늘지 않는다.
        assertThat(pointBalanceRepository.findByMemberId(winner.getId()).orElseThrow().getReservedPoint()).isEqualByComparingTo("310000");
        assertThat(pointBalanceRepository.findByMemberId(first.getId()).orElseThrow().getReservedPoint()).isEqualByComparingTo("0");
        assertThat(pointHistoryRepository.count()).isEqualTo(historyBefore);
        assertThat(bidRepository.findById(topBidId).orElseThrow().isReserved()).isTrue();
    }

    @Test
    void 입찰이_없으면_유찰되고_낙찰_정보와_거래는_만들어지지_않는다() throws Exception {
        Auction auction = newAuction(newMember());
        expire(auction);

        assertThat(processor.close(auction.getId())).isTrue();

        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.FAILED);
        assertThat(reload(auction).getClosedAt()).isNotNull();
        assertThat(resultCount(auction)).isZero();
        assertThat(tradeCount(auction)).isZero();
    }

    @Test
    void 이미_종료된_경매를_다시_처리해도_낙찰_정보와_거래는_한_건이다() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller);
        bid(newMember(), auction, 300_000);
        expire(auction);

        assertThat(processor.close(auction.getId())).isTrue();
        assertThat(processor.close(auction.getId())).isFalse();

        assertThat(resultCount(auction)).isEqualTo(1);
        assertThat(tradeCount(auction)).isEqualTo(1);
        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.SOLD);
    }

    @Test
    void 종료_시각_전이거나_진행_중이_아닌_경매는_처리하지_않는다() throws Exception {
        Member seller = newMember();
        Auction notYet = newAuction(seller);
        bid(newMember(), notYet, 300_000);
        assertThat(processor.close(notYet.getId())).isFalse();
        assertThat(reload(notYet).getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);

        for (AuctionStatus other : new AuctionStatus[] {AuctionStatus.STOPPED, AuctionStatus.FAILED, AuctionStatus.SOLD}) {
            Auction auction = newAuction(seller);
            ReflectionTestUtils.setField(auction, "auctionStatus", other);
            auctionRepository.save(auction);
            expire(auction);
            assertThat(processor.close(auction.getId())).isFalse();
            assertThat(reload(auction).getAuctionStatus()).isEqualTo(other);
            assertThat(resultCount(auction)).isZero();
        }
        assertThat(processor.close(999_999_999L)).isFalse();
    }

    @Test
    void 같은_경매를_동시에_처리해도_한_번만_처리된다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 8; round++) {
            Auction auction = newAuction(seller);
            bid(newMember(), auction, 300_000);
            expire(auction);
            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return processor.close(auction.getId());
                }));
            }
            ready.await();
            go.countDown();
            long processed = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    processed++;
                }
            }
            pool.shutdown();

            assertThat(processed).as("round %d", round).isEqualTo(1);
            assertThat(resultCount(auction)).as("round %d", round).isEqualTo(1);
            assertThat(tradeCount(auction)).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void 한_경매의_처리가_실패해도_다른_경매는_계속_처리한다() throws Exception {
        Member seller = newMember();
        // 최고 입찰 참조가 가리키는 입찰이 없는 경매: 처리하다 예외가 난다.
        Auction broken = newAuction(seller);
        Auction brokenSaved = reload(broken);
        ReflectionTestUtils.setField(brokenSaved, "topBidId", 999_999_999L);
        ReflectionTestUtils.setField(brokenSaved, "endAt", LocalDateTime.now(ZONE).minusYears(1));   // 가장 먼저 처리되도록
        auctionRepository.save(brokenSaved);

        Auction good = newAuction(seller);
        bid(newMember(), good, 300_000);
        expire(good);
        Auction empty = newAuction(seller);
        expire(empty);

        try {
            // 데이터베이스 전체의 종료 시각이 지난 경매를 처리하므로, 아래 확인은 이 테스트가 만든 경매만 본다.
            closingService.closeExpired();

            assertThat(reload(broken).getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);   // 되돌려져 다음 실행에서 다시 시도한다.
            assertThat(reload(good).getAuctionStatus()).isEqualTo(AuctionStatus.SOLD);
            assertThat(reload(empty).getAuctionStatus()).isEqualTo(AuctionStatus.FAILED);
        } finally {
            // 단언이 실패해도 깨진 경매가 남아 이후 종료 처리마다 오류를 내지 않게 정리한다.
            Auction cleanup = reload(broken);
            ReflectionTestUtils.setField(cleanup, "auctionStatus", AuctionStatus.STOPPED);
            auctionRepository.save(cleanup);
        }
    }

    // ---- 낙찰 결과 조회 ----

    @Test
    void 낙찰된_경매의_상세에는_낙찰_결과가_보이고_낙찰자는_일부를_가린다() throws Exception {
        Member seller = newMember();
        Member winner = newMember();
        Member other = newMember();
        Auction auction = newAuction(seller);
        bid(winner, auction, 305_000);
        expire(auction);
        processor.close(auction.getId());
        String path = "/api/auctions/" + auction.getId();

        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionStatus").value("SOLD"))
                .andExpect(jsonPath("$.data.result.maskedWinnerNickname").value(winner.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.result.winningPrice").value(305000))
                .andExpect(jsonPath("$.data.result.confirmedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.result.isWinner").value(false))
                .andExpect(jsonPath("$.data.bidding.canBid").value(false));
        mockMvc.perform(get(path).cookie(cookieOf(winner)))
                .andExpect(jsonPath("$.data.result.isWinner").value(true))
                .andExpect(jsonPath("$.data.bidding.isTopBidder").value(true));
        mockMvc.perform(get(path).cookie(cookieOf(other))).andExpect(jsonPath("$.data.result.isWinner").value(false));
        // 응답에는 낙찰자의 식별 정보가 없다.
        String body = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(winner.getLoginId()).doesNotContain(winner.getEmail()).doesNotContain(winner.getNickname());
        // 종료된 경매에는 더 이상 입찰할 수 없다.
        mockMvc.perform(post(path + "/bids").contentType(MediaType.APPLICATION_JSON).content("{\"bidPrice\":310000}").cookie(cookieOf(other)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AUCTION_NOT_IN_PROGRESS"));
    }

    @Test
    void 유찰되거나_진행_중인_경매의_상세에는_낙찰_결과가_없다() throws Exception {
        Member seller = newMember();
        Auction failed = newAuction(seller);
        expire(failed);
        processor.close(failed.getId());
        Auction open = newAuction(seller);

        mockMvc.perform(get("/api/auctions/" + failed.getId()))
                .andExpect(jsonPath("$.data.auctionStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.result").value((Object) null));
        mockMvc.perform(get("/api/auctions/" + open.getId())).andExpect(jsonPath("$.data.result").value((Object) null));
    }

    @Test
    void 낙찰_후_내_입찰_목록은_낙찰_상태와_내가_낙찰자인지를_보여_준다() throws Exception {
        Member seller = newMember();
        Member winner = newMember();
        Member loser = newMember();
        Auction auction = newAuction(seller);
        bid(loser, auction, 300_000);
        bid(winner, auction, 305_000);
        expire(auction);
        processor.close(auction.getId());

        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(winner)))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("SOLD"))
                .andExpect(jsonPath("$.data.content[0].isTopBidder").value(true))
                .andExpect(jsonPath("$.data.content[0].isReserved").value(true));
        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(loser)))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("SOLD"))
                .andExpect(jsonPath("$.data.content[0].isTopBidder").value(false))
                .andExpect(jsonPath("$.data.content[0].isReserved").value(false));
        // 낙찰된 경매는 내 경매 목록에서도 낙찰 상태다.
        mockMvc.perform(get("/api/members/me/auctions").cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("SOLD"));
        PointHistory last = pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(winner.getId())).reduce((a, b) -> b).orElseThrow();
        assertThat(last.getReservedPointAfter()).isEqualByComparingTo("305000");
    }
}
