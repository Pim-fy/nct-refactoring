package negocut.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auction.service.AuctionCloseProcessor;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;
import negocut.trade.entity.CancelRequestStatus;
import negocut.trade.entity.Dispute;
import negocut.trade.entity.DisputeJudgment;
import negocut.trade.entity.DisputeStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeCancelRequest;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.DisputeRepository;
import negocut.trade.repository.TradeCancelRequestRepository;
import negocut.trade.repository.TradeRepository;
import negocut.trade.service.TradeBatchService;

// 분쟁 접수 (기능 명세서 3-9 분쟁 접수)
// 거래는 실제 경매 흐름(입찰 → 종료 처리)으로 만들어 낙찰자의 예약 포인트(305,000원)가 묶인 상태에서 시작한다.
@SpringBootTest
@AutoConfigureMockMvc
class TradeDisputeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long PRICE = 305_000;
    private static final String ADDRESS = "{\"receiverName\":\"홍길동\",\"receiverPhone\":\"010-1234-5678\",\"zipCode\":\"06236\","
            + "\"address\":\"서울 강남구 테헤란로 123\"}";
    private static final String SHIPMENT = "{\"courierName\":\"대한통운\",\"trackingNumber\":\"1234567890\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private TradeCancelRequestRepository cancelRequestRepository;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private TradeBatchService batchService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("td" + suffix, passwordEncoder.encode("abcd1234"),
                "분쟁" + suffix.substring(2), "td" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            PointBalance balance = pointBalanceRepository.save(PointBalance.createEmpty(member));
            ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(10_000_000));
            pointBalanceRepository.save(balance);
        }
        return member;
    }

    private Member newMember() {
        return createMember(MemberRole.MEMBER, MemberStatus.NORMAL);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    // 낙찰되어 거래(준비)가 만들어진 상태. 거래 식별자를 돌려준다.
    private long newTrade(Member seller, Member buyer) throws Exception {
        Category category = categoryRepository.save(Category.create("분쟁" + TestSuffix.next(), 8400));
        Product product = productRepository.save(Product.create(category, "분쟁 상품 " + TestSuffix.next(), "설명", "상태 설명"));
        Auction auction = auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusMinutes(120)));
        mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":" + PRICE + "}").cookie(cookieOf(buyer))).andExpect(status().isOk());
        Auction saved = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "endAt", LocalDateTime.now(ZONE).minusSeconds(5));
        auctionRepository.save(saved);
        assertThat(auctionCloseProcessor.close(auction.getId())).isTrue();
        return ((Number) entityManager.createQuery("select t.id from Trade t where t.auctionResult.auction.id = :id")
                .setParameter("id", auction.getId()).getSingleResult()).longValue();
    }

    // 배송지 입력과 발송 등록까지 마쳐 진행 상태로 만든다.
    private void ship(long tradeId, Member seller, Member buyer) throws Exception {
        mockMvc.perform(put("/api/trades/" + tradeId + "/delivery").contentType(MediaType.APPLICATION_JSON).content(ADDRESS)
                .cookie(cookieOf(buyer))).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
    }

    private ResultActions postJson(Cookie cookie, String path, String json) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private ResultActions openDispute(Cookie cookie, long tradeId, String reason) throws Exception {
        return postJson(cookie, "/api/trades/" + tradeId + "/disputes", "{\"reason\":\"" + reason + "\"}");
    }

    private long dispute(Member member, long tradeId, String reason) throws Exception {
        String response = openDispute(cookieOf(member), tradeId, reason)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("DISPUTED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.disputeId")).longValue();
    }

    private Trade reload(long tradeId) {
        return tradeRepository.findById(tradeId).orElseThrow();
    }

    private Dispute reloadDispute(long disputeId) {
        return disputeRepository.findById(disputeId).orElseThrow();
    }

    private PointBalance balanceOf(Member member) {
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
    }

    // 관리자 판정(거래 진행)이 끝난 것처럼 분쟁을 닫고 거래를 접수 직전 상태로 되돌린다. 판정 기능은 관리자 구현에서 만든다.
    private void judgeAsResume(long disputeId, long tradeId) {
        Dispute dispute = reloadDispute(disputeId);
        ReflectionTestUtils.setField(dispute, "disputeStatus", DisputeStatus.JUDGED);
        ReflectionTestUtils.setField(dispute, "judgment", DisputeJudgment.RESUME);
        ReflectionTestUtils.setField(dispute, "judgedAt", LocalDateTime.now(ZONE));
        disputeRepository.save(dispute);
        Trade trade = reload(tradeId);
        ReflectionTestUtils.setField(trade, "tradeStatus", dispute.getPreviousTradeStatus());
        tradeRepository.save(trade);
    }

    // ---- 접수 ----

    @Test
    void 준비_상태의_거래에_접수하면_분쟁_상태가_되고_직전_상태가_저장되며_양쪽의_행동이_모두_사라진다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId;

        mockMvc.perform(get(path).cookie(cookieOf(buyer))).andExpect(jsonPath("$.data.availableActions[?(@=='OPEN_DISPUTE')]").exists())
                .andExpect(jsonPath("$.data.dispute").value((Object) null));
        long disputeId = dispute(buyer, tradeId, "기한이 지나도 발송하지 않습니다");

        Dispute saved = reloadDispute(disputeId);
        assertThat(saved.getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(saved.getPreviousTradeStatus()).isEqualTo(TradeStatus.READY);
        assertThat(saved.getJudgment()).isNull();
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        // 접수자에게는 isMine이 true이고 상대방에게는 false다. 양쪽 모두 판정 대기 중이라 할 수 있는 행동이 없다.
        mockMvc.perform(get(path).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.tradeStatus").value("DISPUTED"))
                .andExpect(jsonPath("$.data.dispute.disputeId").value(disputeId))
                .andExpect(jsonPath("$.data.dispute.disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.dispute.isMine").value(true))
                .andExpect(jsonPath("$.data.dispute.reason").value("기한이 지나도 발송하지 않습니다"))
                .andExpect(jsonPath("$.data.dispute.judgment").value((Object) null))
                .andExpect(jsonPath("$.data.availableActions.length()").value(0));
        mockMvc.perform(get(path).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.dispute.isMine").value(false))
                .andExpect(jsonPath("$.data.availableActions.length()").value(0));
        // 포인트는 그대로다.
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));
    }

    @Test
    void 진행_상태의_거래에도_접수할_수_있고_직전_상태는_진행이다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);

        long disputeId = dispute(seller, tradeId, "수령 확인이 되지 않습니다");

        assertThat(reloadDispute(disputeId).getPreviousTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
    }

    @Test
    void 접수하면_처리_중인_취소_요청이_종결로_닫히고_이후_수락은_거부되며_포인트가_반환되지_않는다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String response = postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/cancel-requests", "{\"reason\":\"취소하고 싶습니다\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long requestId = ((Number) JsonPath.read(response, "$.data.requestId")).longValue();

        dispute(seller, tradeId, "분쟁을 접수합니다");

        TradeCancelRequest request = cancelRequestRepository.findById(requestId).orElseThrow();
        assertThat(request.getRequestStatus()).isEqualTo(CancelRequestStatus.CLOSED);
        assertThat(request.getRespondedAt()).isNotNull();
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/cancel-requests/" + requestId + "/accept", "")
                .andExpect(status().isConflict());
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer))).andExpect(jsonPath("$.data.cancelRequest").value((Object) null));
    }

    @Test
    void 분쟁_중에는_당사자가_거래_상태를_바꿀_수_없다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        dispute(buyer, tradeId, "분쟁 중 행동 확인");
        String path = "/api/trades/" + tradeId;

        mockMvc.perform(put(path + "/delivery").contentType(MediaType.APPLICATION_JSON).content(ADDRESS).cookie(cookieOf(buyer)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        postJson(cookieOf(seller), path + "/shipment", SHIPMENT).andExpect(status().isConflict());
        postJson(cookieOf(buyer), path + "/confirm", "").andExpect(status().isConflict());
        postJson(cookieOf(seller), path + "/cancel-requests", "{\"reason\":\"취소\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        // 같은 거래에 처리 중인 분쟁이 있으면 어느 쪽이든 다시 접수할 수 없다.
        openDispute(cookieOf(seller), tradeId, "또 접수").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPUTE_ALREADY_OPEN"));
        openDispute(cookieOf(buyer), tradeId, "또 접수").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPUTE_ALREADY_OPEN"));
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
    }

    @Test
    void 완료_취소_구매_확정_상태의_거래에는_접수할_수_없다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();

        long completed = newTrade(seller, buyer);
        ship(completed, seller, buyer);
        postJson(cookieOf(buyer), "/api/trades/" + completed + "/confirm", "").andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"));
        openDispute(cookieOf(seller), completed, "완료 뒤 접수").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));

        long canceled = newTrade(seller, buyer);
        String response = postJson(cookieOf(buyer), "/api/trades/" + canceled + "/cancel-requests", "{\"reason\":\"취소\"}")
                .andReturn().getResponse().getContentAsString();
        long requestId = ((Number) JsonPath.read(response, "$.data.requestId")).longValue();
        postJson(cookieOf(seller), "/api/trades/" + canceled + "/cancel-requests/" + requestId + "/accept", "").andExpect(status().isOk());
        openDispute(cookieOf(buyer), canceled, "취소 뒤 접수").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));

        long confirmed = newTrade(seller, buyer);
        Trade trade = reload(confirmed);
        ReflectionTestUtils.setField(trade, "tradeStatus", TradeStatus.CONFIRMED);
        tradeRepository.save(trade);
        openDispute(cookieOf(buyer), confirmed, "확정 뒤 접수").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
    }

    @Test
    void 사유_권한_입력_검증() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/disputes";

        // 비었거나 공백(NBSP·전각 포함)뿐이거나 앞뒤 공백을 지운 뒤 500자를 넘는 사유는 거부한다.
        for (String bad : new String[] {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":\"\\u00a0\\u3000 \"}", "{\"reason\":\"\\u200b\\ufeff\\u2060\"}",
                "{\"reason\":\"" + "가".repeat(501) + "\"}", "{\"reason\":\"\\u00a0" + "가".repeat(501) + "\"}"}) {
            postJson(cookieOf(seller), path, bad).andExpect(status().isBadRequest());
        }
        postJson(cookieOf(newMember()), path, "{\"reason\":\"남\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        postJson(null, path, "{\"reason\":\"x\"}").andExpect(status().isUnauthorized());
        postJson(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)), path, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        postJson(cookieOf(buyer), "/api/trades/999999999/disputes", "{\"reason\":\"x\"}").andExpect(status().isNotFound());
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.READY);

        // 정확히 500자와 앞뒤 공백이 붙은 사유는 공백을 지우고 저장한다.
        long disputeId = dispute(seller, tradeId, "\\u00a0 " + "가".repeat(500) + " \\u3000");
        assertThat(reloadDispute(disputeId).getReason()).isEqualTo("가".repeat(500));
    }

    @Test
    void 거래_제한_회원도_분쟁을_접수할_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        Member saved = memberRepository.findById(buyer.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "memberStatus", MemberStatus.RESTRICTED_TRADE);
        memberRepository.save(saved);

        dispute(buyer, tradeId, "제한된 상태에서 접수");

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
    }

    // ---- 횟수 제한과 판정 뒤 표시 ----

    @Test
    void 판정으로_닫힌_뒤에는_다시_접수할_수_있고_당사자별_3회까지만_접수할_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        long last = 0;
        for (int i = 0; i < 3; i++) {
            last = dispute(buyer, tradeId, "같은 사유로 다시 접수");   // 5초 안에 같은 본문도 새로 판정한다.
            judgeAsResume(last, tradeId);
        }

        // 판정이 끝난 가장 최근 분쟁이 결과와 함께 보이고, 3회를 쓴 낙찰자에게는 접수 행동이 없다.
        String path = "/api/trades/" + tradeId;
        mockMvc.perform(get(path).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.dispute.disputeId").value(last))
                .andExpect(jsonPath("$.data.dispute.disputeStatus").value("JUDGED"))
                .andExpect(jsonPath("$.data.dispute.judgment").value("RESUME"))
                .andExpect(jsonPath("$.data.dispute.judgedAt").exists())
                .andExpect(jsonPath("$.data.availableActions[?(@=='OPEN_DISPUTE')]").doesNotExist())
                .andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").exists());
        openDispute(cookieOf(buyer), tradeId, "네 번째").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPUTE_LIMIT_EXCEEDED"));
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.READY);

        // 판매자의 횟수는 따로 센다.
        mockMvc.perform(get(path).cookie(cookieOf(seller))).andExpect(jsonPath("$.data.availableActions[?(@=='OPEN_DISPUTE')]").exists())
                .andExpect(jsonPath("$.data.dispute.isMine").value(false));
        dispute(seller, tradeId, "판매자의 첫 접수");
    }

    // ---- 스케줄러 제외 ----

    @Test
    void 분쟁_중인_거래는_자동_구매_확정_미이행_표시_지급_재시도에서_제외된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();

        // 진행 상태에서 구매 확정 기한이 지난 거래
        long shipped = newTrade(seller, buyer);
        ship(shipped, seller, buyer);
        dispute(buyer, shipped, "자동 확정 제외 확인");
        Trade shippedTrade = reload(shipped);
        ReflectionTestUtils.setField(shippedTrade, "confirmDueAt", LocalDateTime.now(ZONE).minusMinutes(1));
        tradeRepository.save(shippedTrade);

        // 준비 상태에서 발송 기한이 지난 거래
        long ready = newTrade(seller, buyer);
        dispute(seller, ready, "미이행 표시 제외 확인");
        Trade readyTrade = reload(ready);
        ReflectionTestUtils.setField(readyTrade, "shipDueAt", LocalDateTime.now(ZONE).minusMinutes(1));
        tradeRepository.save(readyTrade);

        batchService.autoConfirmDue();
        batchService.markUnfulfilledDue();
        batchService.retryPayouts();

        assertThat(reload(shipped).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(reload(shipped).getConfirmedAt()).isNull();
        assertThat(reload(ready).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(reload(ready).isUnfulfilled()).isFalse();
        assertThat(pointHistoryRepository.findAll().stream()
                .filter(h -> h.getHistoryType() == PointHistoryType.PAYOUT && h.getTradeId() != null
                        && (h.getTradeId() == shipped || h.getTradeId() == ready)).toList()).isEmpty();
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE * 2));
    }

    // ---- 경쟁 ----

    @Test
    void 양쪽이_동시에_접수해도_처리_중인_분쟁은_한_건이다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (Member member : List.of(seller, buyer)) {
            results.add(pool.submit(() -> {
                go.await();
                return openDispute(cookieOf(member), tradeId, "동시 접수 " + member.getId()).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
        long processing = entityManager.createQuery(
                "select count(d) from Dispute d where d.trade.id = :id and d.disputeStatus = negocut.trade.entity.DisputeStatus.OPEN",
                Long.class).setParameter("id", tradeId).getSingleResult();
        assertThat(processing).isEqualTo(1);
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
    }

    private int countHistory(Member member, PointHistoryType type, long tradeId) {
        return (int) pointHistoryRepository.findAll().stream()
                .filter(h -> h.getHistoryType() == type && h.getTradeId() != null && h.getTradeId() == tradeId
                        && h.getMember().getId().equals(member.getId())).count();
    }

    @Test
    void 취소_수락과_분쟁_접수가_동시에_겹쳐도_하나만_성립하고_포인트가_이중으로_움직이지_않는다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 8; round++) {
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            String response = postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/cancel-requests", "{\"reason\":\"동시 확인 " + round + "\"}")
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long requestId = ((Number) JsonPath.read(response, "$.data.requestId")).longValue();

            final int roundNo = round;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> accept = pool.submit(() -> {
                go.await();
                return postJson(cookieOf(seller), "/api/trades/" + tradeId + "/cancel-requests/" + requestId + "/accept", "")
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> dispute = pool.submit(() -> {
                go.await();
                return openDispute(cookieOf(buyer), tradeId, "동시 접수 " + roundNo).andReturn().getResponse().getStatus();
            });
            go.countDown();
            int acceptStatus = accept.get();
            int disputeStatus = dispute.get();
            pool.shutdown();

            TradeStatus finalStatus = reload(tradeId).getTradeStatus();
            CancelRequestStatus requestStatus = cancelRequestRepository.findById(requestId).orElseThrow().getRequestStatus();
            assertThat(finalStatus).as("round %d: 수락 %d, 접수 %d", round, acceptStatus, disputeStatus)
                    .isIn(TradeStatus.CANCELED, TradeStatus.DISPUTED);
            if (finalStatus == TradeStatus.CANCELED) {
                assertThat(acceptStatus).isEqualTo(200);
                assertThat(disputeStatus).isEqualTo(409);
                assertThat(requestStatus).isEqualTo(CancelRequestStatus.ACCEPTED);
                assertThat(countHistory(buyer, PointHistoryType.REFUND, tradeId)).isEqualTo(1);
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo("0");
            } else {
                assertThat(disputeStatus).isEqualTo(200);
                assertThat(acceptStatus).isEqualTo(409);
                assertThat(requestStatus).isEqualTo(CancelRequestStatus.CLOSED);
                assertThat(countHistory(buyer, PointHistoryType.REFUND, tradeId)).isZero();
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));
            }
        }
    }

    @Test
    void 자동_구매_확정과_분쟁_접수가_동시에_겹쳐도_하나만_성립하고_지급이_이중으로_나가지_않는다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 8; round++) {
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);
            Trade trade = reload(tradeId);
            ReflectionTestUtils.setField(trade, "confirmDueAt", LocalDateTime.now(ZONE).minusMinutes(1));
            tradeRepository.save(trade);

            final int roundNo = round;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> auto = pool.submit(() -> {
                go.await();
                return batchService.autoConfirmDue();
            });
            Future<Integer> dispute = pool.submit(() -> {
                go.await();
                return openDispute(cookieOf(seller), tradeId, "동시 접수 " + roundNo).andReturn().getResponse().getStatus();
            });
            go.countDown();
            auto.get();
            int disputeStatus = dispute.get();
            pool.shutdown();

            TradeStatus finalStatus = reload(tradeId).getTradeStatus();
            assertThat(finalStatus).as("round %d: 접수 %d", round, disputeStatus).isIn(TradeStatus.DISPUTED, TradeStatus.COMPLETED);
            if (finalStatus == TradeStatus.DISPUTED) {
                assertThat(disputeStatus).isEqualTo(200);
                assertThat(reload(tradeId).getConfirmedAt()).isNull();
                assertThat(countHistory(buyer, PointHistoryType.PAYOUT, tradeId)).isZero();
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));
            } else {
                assertThat(disputeStatus).isEqualTo(409);
                assertThat(reload(tradeId).isAutoConfirmed()).isTrue();
                assertThat(countHistory(buyer, PointHistoryType.PAYOUT, tradeId)).isEqualTo(1);
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo("0");
            }
        }
    }

    @Test
    void 분쟁_접수와_구매_확정이_동시에_겹쳐도_하나만_성립하고_포인트가_이중으로_움직이지_않는다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 8; round++) {
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> disputeResult = pool.submit(() -> {
                go.await();
                return openDispute(cookieOf(seller), tradeId, "동시 처리 확인 " + 0).andReturn().getResponse().getStatus();
            });
            Future<Integer> confirm = pool.submit(() -> {
                go.await();
                return postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andReturn().getResponse().getStatus();
            });
            go.countDown();
            int disputeStatus = disputeResult.get();
            int confirmStatus = confirm.get();
            pool.shutdown();

            TradeStatus finalStatus = reload(tradeId).getTradeStatus();
            int payouts = (int) pointHistoryRepository.findAll().stream()
                    .filter(h -> h.getHistoryType() == PointHistoryType.PAYOUT && h.getTradeId() != null && h.getTradeId() == tradeId
                            && h.getMember().getId().equals(buyer.getId())).count();
            assertThat(finalStatus).as("round %d: 접수 %d, 확정 %d", round, disputeStatus, confirmStatus)
                    .isIn(TradeStatus.DISPUTED, TradeStatus.COMPLETED);
            if (finalStatus == TradeStatus.DISPUTED) {
                assertThat(disputeStatus).isEqualTo(200);
                assertThat(confirmStatus).isEqualTo(409);
                assertThat(payouts).isZero();
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));
            } else {
                assertThat(confirmStatus).isEqualTo(200);
                assertThat(disputeStatus).isEqualTo(409);
                assertThat(payouts).isEqualTo(1);
                assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo("0");
            }
        }
    }
}
