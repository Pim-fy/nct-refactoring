package negocut.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Bid;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.BidRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.entity.PointHistory;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;

// 입찰과 입찰 내역, 내 입찰 목록 (기능 명세서 3-6)
// 경매는 저장소로 직접 만들고, 입찰은 실제 API로 한다. 시작가 300,000원, 입찰 단위 5,000원인 경매를 기본으로 쓴다.
@SpringBootTest
@AutoConfigureMockMvc
class BidTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status, long totalPoint) {
        String suffix = TestSuffix.next();
        Member member = Member.create("bd" + suffix, passwordEncoder.encode("abcd1234"),
                "입찰" + suffix.substring(2), "bd" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            PointBalance balance = pointBalanceRepository.save(PointBalance.createEmpty(member));
            ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(totalPoint));
            pointBalanceRepository.save(balance);
        }
        return member;
    }

    // 보유 포인트가 1,000만인 정상 회원
    private Member newMember() {
        return createMember(MemberRole.MEMBER, MemberStatus.NORMAL, 10_000_000);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Category newCategory() {
        return categoryRepository.save(Category.create("입찰" + TestSuffix.next(), 8600));
    }

    private Auction newAuction(Member seller, Category category, long minutesToEnd) {
        Product product = productRepository.save(Product.create(category, "입찰 상품 " + TestSuffix.next(), "설명", "상태 설명"));
        return auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusMinutes(minutesToEnd)));
    }

    private Auction newAuction(Member seller) {
        return newAuction(seller, newCategory(), 120);
    }

    private ResultActions bid(Cookie cookie, long auctionId, String json) throws Exception {
        var request = post("/api/auctions/" + auctionId + "/bids").contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private ResultActions bid(Cookie cookie, long auctionId, long price) throws Exception {
        return bid(cookie, auctionId, "{\"bidPrice\":" + price + "}");
    }

    private PointBalance balanceOf(Member member) {
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
    }

    private List<PointHistory> historyOf(Member member, PointHistoryType type) {
        return pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(member.getId()) && h.getHistoryType() == type).toList();
    }

    private Auction reload(Auction auction) {
        return auctionRepository.findById(auction.getId()).orElseThrow();
    }

    private void setStatus(Auction auction, AuctionStatus status) {
        ReflectionTestUtils.setField(auction, "auctionStatus", status);
        auctionRepository.save(auction);
    }

    // ---- 입찰 ----

    @Test
    void 첫_입찰은_시작가_이상이면_되고_입찰_금액만큼_포인트가_예약된다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);

        bid(cookieOf(bidder), auction.getId(), 300_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auctionId").value(auction.getId()))
                .andExpect(jsonPath("$.data.bidPrice").value(300000))
                .andExpect(jsonPath("$.data.currentPrice").value(300000))
                .andExpect(jsonPath("$.data.isTopBidder").value(true))
                .andExpect(jsonPath("$.data.minimumBidPrice").value(305000))
                .andExpect(jsonPath("$.data.point.totalPoint").value(10000000))
                .andExpect(jsonPath("$.data.point.reservedPoint").value(300000))
                .andExpect(jsonPath("$.data.point.availablePoint").value(9700000));

        Auction saved = reload(auction);
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo("300000");
        assertThat(saved.getBidCount()).isEqualTo(1);
        Bid top = bidRepository.findById(saved.getTopBidId()).orElseThrow();
        assertThat(top.isReserved()).isTrue();
        assertThat(top.getBidPrice()).isEqualByComparingTo("300000");
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("300000");
        List<PointHistory> reserves = historyOf(bidder, PointHistoryType.RESERVE);
        assertThat(reserves).hasSize(1);
        assertThat(reserves.get(0).getAmount()).isEqualByComparingTo("300000");
        assertThat(reserves.get(0).getReservedPointAfter()).isEqualByComparingTo("300000");
        assertThat(reserves.get(0).getAuction().getId()).isEqualTo(auction.getId());
    }

    @Test
    void 더_높은_입찰이_들어오면_직전_최고_입찰자의_예약이_풀리고_새_입찰자가_예약한다() throws Exception {
        Member seller = newMember();
        Member first = newMember();
        Member second = newMember();
        Auction auction = newAuction(seller);

        bid(cookieOf(first), auction.getId(), 300_000).andExpect(status().isOk());
        Long firstBidId = reload(auction).getTopBidId();
        bid(cookieOf(second), auction.getId(), 305_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.point.reservedPoint").value(305000));

        assertThat(balanceOf(first).getReservedPoint()).isEqualByComparingTo("0");
        assertThat(balanceOf(first).getTotalPoint()).isEqualByComparingTo("10000000");   // 보유 포인트는 그대로
        assertThat(balanceOf(second).getReservedPoint()).isEqualByComparingTo("305000");
        List<PointHistory> releases = historyOf(first, PointHistoryType.RELEASE);
        assertThat(releases).hasSize(1);
        assertThat(releases.get(0).getAmount()).isEqualByComparingTo("300000");
        assertThat(releases.get(0).getReservedPointAfter()).isEqualByComparingTo("0");
        assertThat(bidRepository.findById(firstBidId).orElseThrow().isReserved()).isFalse();
        Auction saved = reload(auction);
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo("305000");
        assertThat(saved.getBidCount()).isEqualTo(2);
        assertThat(bidRepository.findById(saved.getTopBidId()).orElseThrow().isReserved()).isTrue();
    }

    @Test
    void 최고_입찰자가_다시_입찰하면_차액만_추가로_예약하고_해제는_없다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);
        Cookie cookie = cookieOf(bidder);

        bid(cookie, auction.getId(), 300_000).andExpect(status().isOk());
        Long firstBidId = reload(auction).getTopBidId();
        bid(cookie, auction.getId(), 320_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.point.reservedPoint").value(320000));

        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("320000");
        List<PointHistory> reserves = historyOf(bidder, PointHistoryType.RESERVE);
        assertThat(reserves).extracting(h -> h.getAmount().intValue()).containsExactlyInAnyOrder(300000, 20000);
        assertThat(historyOf(bidder, PointHistoryType.RELEASE)).isEmpty();
        assertThat(bidRepository.findById(firstBidId).orElseThrow().isReserved()).isFalse();   // 예약은 새 입찰로 넘어간다.
        assertThat(bidRepository.findById(reload(auction).getTopBidId()).orElseThrow().isReserved()).isTrue();
    }

    @Test
    void 최고_입찰자의_재입찰은_사용_가능_포인트를_차액으로_판단한다() throws Exception {
        Member seller = newMember();
        // 보유 350,000: 300,000을 예약하면 사용 가능이 50,000이라 처음부터 새로 340,000을 예약할 수는 없지만, 차액 40,000은 된다.
        Member bidder = createMember(MemberRole.MEMBER, MemberStatus.NORMAL, 350_000);
        Auction auction = newAuction(seller);
        Cookie cookie = cookieOf(bidder);

        bid(cookie, auction.getId(), 300_000).andExpect(status().isOk());
        bid(cookie, auction.getId(), 340_000).andExpect(status().isOk());
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("340000");
        // 차액 15,000이 사용 가능 포인트(10,000)를 넘으면 거부한다.
        bid(cookie, auction.getId(), 355_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_NOT_ENOUGH"));
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("340000");
    }

    // ---- 검증 ----

    @Test
    void 진행_중이_아니거나_마감_시각이_지난_경매에는_입찰할_수_없다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Cookie cookie = cookieOf(bidder);
        for (AuctionStatus closed : new AuctionStatus[] {AuctionStatus.SOLD, AuctionStatus.FAILED, AuctionStatus.STOPPED}) {
            Auction auction = newAuction(seller);
            setStatus(auction, closed);
            bid(cookie, auction.getId(), 300_000).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("AUCTION_NOT_IN_PROGRESS"));
        }
        Auction expired = newAuction(seller, newCategory(), -1);   // 종료 처리를 기다리는 경매
        bid(cookie, expired.getId(), 300_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUCTION_NOT_IN_PROGRESS"));
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("0");
        assertThat(historyOf(bidder, PointHistoryType.RESERVE)).isEmpty();
    }

    @Test
    void 판매자는_본인_경매에_입찰할_수_없다() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller);

        bid(cookieOf(seller), auction.getId(), 300_000).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BID_SELF_AUCTION_NOT_ALLOWED"));
        assertThat(reload(auction).getBidCount()).isZero();
    }

    @Test
    void 입찰_단위의_배수가_아니거나_정수_표기가_아니면_400이다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);

        for (String price : new String[] {"302500", "300001", "305000.0", "3.05E+5", "300000.5"}) {
            bid(cookieOf(bidder), auction.getId(), "{\"bidPrice\":" + price + "}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("BID_UNIT_MISMATCH"));
        }
        assertThat(reload(auction).getBidCount()).isZero();
    }

    @Test
    void 최소_입찰_금액보다_낮으면_400이다() throws Exception {
        Member seller = newMember();
        Member first = newMember();
        Member second = newMember();
        Auction auction = newAuction(seller);

        // 첫 입찰은 시작가 이상이어야 한다.
        bid(cookieOf(first), auction.getId(), 295_000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BID_AMOUNT_TOO_LOW"));
        bid(cookieOf(first), auction.getId(), 300_000).andExpect(status().isOk());
        // 이후에는 현재가에 입찰 단위를 더한 금액 이상이어야 한다.
        bid(cookieOf(second), auction.getId(), 300_000).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BID_AMOUNT_TOO_LOW"));
        bid(cookieOf(second), auction.getId(), 305_000).andExpect(status().isOk());
        assertThat(reload(auction).getBidCount()).isEqualTo(2);
        assertThat(balanceOf(second).getReservedPoint()).isEqualByComparingTo("305000");
    }

    @Test
    void 사용_가능_포인트를_넘으면_409이고_아무것도_바뀌지_않는다() throws Exception {
        Member seller = newMember();
        Member bidder = createMember(MemberRole.MEMBER, MemberStatus.NORMAL, 500_000);
        Auction other = newAuction(seller);
        Auction auction = newAuction(seller);
        Cookie cookie = cookieOf(bidder);

        bid(cookie, other.getId(), 300_000).andExpect(status().isOk());   // 다른 경매에 300,000 예약 → 사용 가능 200,000
        bid(cookie, auction.getId(), 300_000).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POINT_NOT_ENOUGH"));

        assertThat(reload(auction).getBidCount()).isZero();
        assertThat(reload(auction).getTopBidId()).isNull();
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("300000");
        // 실패한 입찰 때문에 직전 최고 입찰자의 예약이 풀리지도 않는다.
        Member rival = createMember(MemberRole.MEMBER, MemberStatus.NORMAL, 100_000);
        bid(cookieOf(rival), other.getId(), 305_000).andExpect(status().isConflict());
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo("300000");
        assertThat(historyOf(bidder, PointHistoryType.RELEASE)).isEmpty();
    }

    @Test
    void 입찰_금액이_비었거나_범위_밖이면_400이다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);
        Cookie cookie = cookieOf(bidder);

        for (String json : new String[] {"{}", "{\"bidPrice\":null}", "{\"bidPrice\":0}", "{\"bidPrice\":-5000}", "{\"bidPrice\":1000000001}"}) {
            bid(cookie, auction.getId(), json).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        }
        bid(cookie, auction.getId(), "{\"bidPrice\":\"abc\"}").andExpect(status().isBadRequest());
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                bid(cookie, auction.getId(), "{\"bidPrice\":1E+100000000}").andExpect(status().isBadRequest()));
        assertThat(reload(auction).getBidCount()).isZero();
    }

    @Test
    void 비로그인은_401이고_관리자는_403이며_거래_제한_회원은_입찰할_수_없고_없는_경매는_404다() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller);

        bid(null, auction.getId(), 300_000).andExpect(status().isUnauthorized());
        bid(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL, 0)), auction.getId(), 300_000)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        Member restricted = createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE, 10_000_000);
        bid(cookieOf(restricted), auction.getId(), 300_000).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));
        bid(cookieOf(newMember()), 999_999_999L, 300_000).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AUCTION_NOT_FOUND"));
        assertThat(reload(auction).getBidCount()).isZero();
        assertThat(balanceOf(restricted).getReservedPoint()).isEqualByComparingTo("0");
    }

    // ---- 동시성 ----

    @Test
    void 같은_경매에_동시에_입찰해도_최고_입찰자는_한_명이고_예약은_최고_입찰만_남는다() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller);
        int threads = 8;
        List<Member> bidders = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            bidders.add(newMember());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Cookie cookie = cookieOf(bidders.get(i));
            long price = 300_000 + i * 5_000L;   // 서로 다른 금액으로 보내 중복 요청 방지가 아니라 입찰 규칙까지 도달시킨다.
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return bid(cookie, auction.getId(), price).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).allMatch(s -> s == 200 || s == 400);   // 늦게 처리된 낮은 금액은 최소 입찰 금액 미달이다.
        Auction saved = reload(auction);
        List<Bid> saveds = bidRepository.findAll().stream().filter(b -> b.getAuction().getId().equals(auction.getId())).toList();
        assertThat(saveds).hasSize(statuses.stream().filter(s -> s == 200).count() > 0 ? (int) statuses.stream().filter(s -> s == 200).count() : 0);
        assertThat(saved.getBidCount()).isEqualTo(saveds.size());
        // 현재 최고 입찰은 성공한 입찰 중 가장 높은 금액이고, 예약이 유지 중인 입찰은 그 한 건뿐이다.
        BigDecimal maxPrice = saveds.stream().map(b -> b.getBidPrice()).max((a, b) -> a.compareTo(b)).orElseThrow();
        assertThat(saved.getCurrentPrice()).isEqualByComparingTo(maxPrice);
        assertThat(saveds.stream().filter(b -> b.isReserved()).count()).isEqualTo(1);
        assertThat(bidRepository.findById(saved.getTopBidId()).orElseThrow().isReserved()).isTrue();
        // 모든 입찰자의 예약을 더하면 최고 입찰 금액과 같다.
        BigDecimal reservedSum = bidders.stream().map(m -> balanceOf(m).getReservedPoint()).reduce(BigDecimal.ZERO, (a, b) -> a.add(b));
        assertThat(reservedSum).isEqualByComparingTo(maxPrice);
        // 가장 높은 금액(8번째)은 누가 먼저 처리돼도 항상 성공한다.
        assertThat(statuses.get(threads - 1)).isEqualTo(200);
    }

    @Test
    void 같은_회원이_같은_경매에_동시에_재입찰해도_예약은_최고_입찰_금액과_같다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);
        Cookie cookie = cookieOf(bidder);
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long price = 300_000 + i * 5_000L;
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return bid(cookie, auction.getId(), price).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();
        for (Future<Integer> result : results) {
            result.get();
        }
        pool.shutdown();

        Auction saved = reload(auction);
        assertThat(balanceOf(bidder).getReservedPoint()).isEqualByComparingTo(saved.getCurrentPrice());
        assertThat(bidRepository.findAll().stream().filter(b -> b.getAuction().getId().equals(auction.getId()) && b.isReserved()).count()).isEqualTo(1);
        assertThat(historyOf(bidder, PointHistoryType.RELEASE)).isEmpty();
    }

    @Test
    void 두_회원이_서로_다른_경매에서_서로를_밀어내는_입찰이_겹쳐도_교착_상태가_되지_않는다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 10; round++) {
            Member a = newMember();
            Member b = newMember();
            Auction x = newAuction(seller);
            Auction y = newAuction(seller);
            bid(cookieOf(b), x.getId(), 300_000).andExpect(status().isOk());   // x의 최고 입찰자는 b
            bid(cookieOf(a), y.getId(), 300_000).andExpect(status().isOk());   // y의 최고 입찰자는 a

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> aOnX = pool.submit(() -> {
                go.await();
                return bid(cookieOf(a), x.getId(), 305_000).andReturn().getResponse().getStatus();
            });
            Future<Integer> bOnY = pool.submit(() -> {
                go.await();
                return bid(cookieOf(b), y.getId(), 305_000).andReturn().getResponse().getStatus();
            });
            go.countDown();
            assertThat(aOnX.get()).as("round %d a→x", round).isEqualTo(200);
            assertThat(bOnY.get()).as("round %d b→y", round).isEqualTo(200);
            pool.shutdown();

            // a는 x에 305,000을 예약하고 y의 300,000은 풀렸다. b도 같다.
            assertThat(balanceOf(a).getReservedPoint()).isEqualByComparingTo("305000");
            assertThat(balanceOf(b).getReservedPoint()).isEqualByComparingTo("305000");
        }
    }

    // ---- 입찰 내역 ----

    @Test
    void 입찰_내역은_공개이고_높은_금액_순이며_입찰자는_일부를_가리고_본인_입찰을_표시한다() throws Exception {
        Member seller = newMember();
        Member first = newMember();
        Member second = newMember();
        Auction auction = newAuction(seller);
        bid(cookieOf(first), auction.getId(), 300_000).andExpect(status().isOk());
        bid(cookieOf(second), auction.getId(), 305_000).andExpect(status().isOk());
        bid(cookieOf(first), auction.getId(), 310_000).andExpect(status().isOk());

        // 비로그인도 볼 수 있다.
        mockMvc.perform(get("/api/auctions/" + auction.getId() + "/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].bidPrice").value(310000))
                .andExpect(jsonPath("$.data.content[1].bidPrice").value(305000))
                .andExpect(jsonPath("$.data.content[2].bidPrice").value(300000))
                .andExpect(jsonPath("$.data.content[0].maskedBidderNickname").value(first.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.content[0].isMine").value(false))
                .andExpect(jsonPath("$.data.content[0].bidAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
        // 로그인하면 본인 입찰이 표시된다.
        mockMvc.perform(get("/api/auctions/" + auction.getId() + "/bids").cookie(cookieOf(first)))
                .andExpect(jsonPath("$.data.content[0].isMine").value(true))
                .andExpect(jsonPath("$.data.content[1].isMine").value(false))
                .andExpect(jsonPath("$.data.content[2].isMine").value(true));
        // 응답에는 입찰자의 식별 정보가 없다.
        String body = mockMvc.perform(get("/api/auctions/" + auction.getId() + "/bids")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(first.getLoginId()).doesNotContain(first.getEmail()).doesNotContain(first.getNickname());
        // 페이지
        mockMvc.perform(get("/api/auctions/" + auction.getId() + "/bids?page=1&size=2"))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        for (String bad : new String[] {"size=0", "size=101", "page=-1", "page=2147483647&size=100"}) {
            mockMvc.perform(get("/api/auctions/" + auction.getId() + "/bids?" + bad)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/auctions/999999999/bids")).andExpect(status().isNotFound());
    }

    @Test
    void 중단된_경매의_입찰_내역과_상세는_판매자와_입찰자만_볼_수_있다() throws Exception {
        Member seller = newMember();
        Member bidder = newMember();
        Auction auction = newAuction(seller);
        bid(cookieOf(bidder), auction.getId(), 300_000).andExpect(status().isOk());
        ReflectionTestUtils.setField(auction, "stoppedReason", "운영 정책 위반");
        setStatus(auction, AuctionStatus.STOPPED);
        String detail = "/api/auctions/" + auction.getId();

        for (Member allowed : List.of(seller, bidder)) {
            mockMvc.perform(get(detail).cookie(cookieOf(allowed)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.stoppedReason").value("운영 정책 위반"));
            mockMvc.perform(get(detail + "/bids").cookie(cookieOf(allowed))).andExpect(status().isOk());
        }
        mockMvc.perform(get(detail)).andExpect(status().isNotFound());
        mockMvc.perform(get(detail + "/bids")).andExpect(status().isNotFound());
        mockMvc.perform(get(detail).cookie(cookieOf(newMember()))).andExpect(status().isNotFound());
        mockMvc.perform(get(detail + "/bids").cookie(cookieOf(newMember()))).andExpect(status().isNotFound());
    }

    @Test
    void 상세는_로그인한_회원이_최고_입찰자인지_알려_준다() throws Exception {
        Member seller = newMember();
        Member top = newMember();
        Member other = newMember();
        Auction auction = newAuction(seller);
        bid(cookieOf(top), auction.getId(), 300_000).andExpect(status().isOk());
        String path = "/api/auctions/" + auction.getId();

        mockMvc.perform(get(path).cookie(cookieOf(top))).andExpect(jsonPath("$.data.bidding.isTopBidder").value(true));
        mockMvc.perform(get(path).cookie(cookieOf(other))).andExpect(jsonPath("$.data.bidding.isTopBidder").value(false));
        mockMvc.perform(get(path)).andExpect(jsonPath("$.data.bidding.isTopBidder").value(false));
        // 밀려나면 더 이상 최고 입찰자가 아니다.
        bid(cookieOf(other), auction.getId(), 305_000).andExpect(status().isOk());
        mockMvc.perform(get(path).cookie(cookieOf(top))).andExpect(jsonPath("$.data.bidding.isTopBidder").value(false));
        mockMvc.perform(get(path).cookie(cookieOf(other)))
                .andExpect(jsonPath("$.data.bidding.isTopBidder").value(true))
                .andExpect(jsonPath("$.data.bidding.minimumBidPrice").value(310000));
    }

    // ---- 내 입찰 목록 ----

    @Test
    void 내_입찰_목록은_경매마다_한_줄이고_내가_마지막으로_입찰한_경매가_먼저이며_최고_입찰_여부와_예약을_보여_준다() throws Exception {
        Member seller = newMember();
        Member me = newMember();
        Member rival = newMember();
        Auction a = newAuction(seller);
        Auction b = newAuction(seller);
        Auction c = newAuction(seller);
        Cookie mine = cookieOf(me);

        bid(mine, a.getId(), 300_000).andExpect(status().isOk());
        bid(mine, b.getId(), 300_000).andExpect(status().isOk());
        bid(mine, a.getId(), 310_000).andExpect(status().isOk());   // a에 두 번 입찰
        bid(cookieOf(rival), b.getId(), 305_000).andExpect(status().isOk());   // b에서는 밀려난다
        bid(cookieOf(rival), c.getId(), 300_000).andExpect(status().isOk());   // 내가 입찰하지 않은 경매

        String json = mockMvc.perform(get("/api/members/me/bids").cookie(mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(json, "$.data.content[*].auctionId");
        assertThat(ids.stream().map(n -> n.longValue()).toList()).containsExactly(a.getId(), b.getId());   // 마지막 입찰이 최근인 순서

        mockMvc.perform(get("/api/members/me/bids").cookie(mine))
                .andExpect(jsonPath("$.data.content[0].myBidPrice").value(310000))
                .andExpect(jsonPath("$.data.content[0].currentPrice").value(310000))
                .andExpect(jsonPath("$.data.content[0].isTopBidder").value(true))
                .andExpect(jsonPath("$.data.content[0].isReserved").value(true))
                .andExpect(jsonPath("$.data.content[0].auctionStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].endAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.content[0].thumbnailUrl").value((Object) null))
                .andExpect(jsonPath("$.data.content[1].myBidPrice").value(300000))
                .andExpect(jsonPath("$.data.content[1].currentPrice").value(305000))
                .andExpect(jsonPath("$.data.content[1].isTopBidder").value(false))
                .andExpect(jsonPath("$.data.content[1].isReserved").value(false));
        // 다른 회원의 입찰은 보이지 않는다.
        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(rival))).andExpect(jsonPath("$.data.totalElements").value(2));
        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(newMember()))).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 내_입찰_목록의_페이지_값과_권한을_확인한다() throws Exception {
        Member me = newMember();
        mockMvc.perform(get("/api/members/me/bids")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL, 0))))
                .andExpect(status().isForbidden());
        // 거래 제한 회원도 조회할 수 있다.
        mockMvc.perform(get("/api/members/me/bids").cookie(cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE, 0))))
                .andExpect(status().isOk());
        for (String bad : new String[] {"size=0", "size=101", "page=-1", "page=2147483647&size=100"}) {
            mockMvc.perform(get("/api/members/me/bids?" + bad).cookie(cookieOf(me))).andExpect(status().isBadRequest());
        }
    }
}
