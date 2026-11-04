package negocut.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;
import negocut.trade.entity.Dispute;
import negocut.trade.entity.DisputeJudgment;
import negocut.trade.entity.DisputeStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.DisputeRepository;
import negocut.trade.repository.TradeRepository;
import negocut.trade.service.TradeBatchService;

// 관리자 분쟁 관리: 분쟁 목록, 분쟁 처리를 위한 거래 상세, 분쟁 판정 (기능 명세서 3-10 분쟁 판정)
@SpringBootTest
@AutoConfigureMockMvc
class AdminDisputeTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long PRICE = 305_000;
    private static final long START_POINT = 10_000_000;
    private static final String ADDRESS = "{\"receiverName\":\"홍길동\",\"receiverPhone\":\"010-1234-5678\",\"zipCode\":\"06236\","
            + "\"address\":\"서울 강남구 테헤란로 123\"}";
    private static final String SHIPMENT = "{\"courierName\":\"대한통운\",\"trackingNumber\":\"1234567890\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private TradeBatchService batchService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, boolean withBalance) {
        String suffix = TestSuffix.next();
        Member member = Member.create("dj" + suffix, passwordEncoder.encode("abcd1234"),
                "판정" + suffix.substring(2), "dj" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.NORMAL);
        member = memberRepository.save(member);
        if (withBalance && role == MemberRole.MEMBER) {
            PointBalance balance = pointBalanceRepository.save(PointBalance.createEmpty(member));
            ReflectionTestUtils.setField(balance, "totalPoint", BigDecimal.valueOf(START_POINT));
            pointBalanceRepository.save(balance);
        }
        return member;
    }

    private Member newMember() {
        return createMember(MemberRole.MEMBER, true);
    }

    private Member newAdmin() {
        return createMember(MemberRole.ADMIN, false);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    // 낙찰되어 거래(준비)가 만들어진 상태. 낙찰자의 예약 포인트는 305,000이다.
    private long newTrade(Member seller, Member buyer) throws Exception {
        Category category = categoryRepository.save(Category.create("판정" + TestSuffix.next(), 8300));
        Product product = productRepository.save(Product.create(category, "판정 상품 " + TestSuffix.next(), "설명", "상태 설명"));
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
        mockMvc.perform(post("/api/trades/" + tradeId + "/shipment").contentType(MediaType.APPLICATION_JSON).content(SHIPMENT)
                .cookie(cookieOf(seller))).andExpect(status().isOk());
    }

    private long dispute(Member member, long tradeId, String reason) throws Exception {
        String response = mockMvc.perform(post("/api/trades/" + tradeId + "/disputes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + reason + "\"}").cookie(cookieOf(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tradeStatus").value("DISPUTED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.disputeId")).longValue();
    }

    // 화면이 본 마지막 판정 시각(expectedJudgedAt)을 함께 보낸다. 생략하면 지금의 마지막 판정 시각을 본 것으로 한다.
    private ResultActions judge(Cookie cookie, long disputeId, String judgment, String reason) throws Exception {
        return judge(cookie, disputeId, judgment, reason, currentJudgedAt(disputeId));
    }

    // 분쟁의 마지막 판정 시각을 요청 형식으로 돌려준다. 판정한 적이 없거나 없는 분쟁이면 null이다.
    private String currentJudgedAt(long disputeId) {
        entityManager.clear();
        return disputeRepository.findById(disputeId).map(Dispute::getJudgedAt)
                .map(time -> time.atZone(ZONE).toOffsetDateTime().toString()).orElse(null);
    }

    private ResultActions judge(Cookie cookie, long disputeId, String judgment, String reason, String expectedJudgedAt) throws Exception {
        var request = post("/api/admin/disputes/" + disputeId + "/judge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"judgment\":" + (judgment == null ? "null" : "\"" + judgment + "\"") + ",\"judgmentReason\":\"" + reason + "\""
                        + (expectedJudgedAt == null ? "" : ",\"expectedJudgedAt\":\"" + expectedJudgedAt + "\"") + "}");
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private Trade reload(long tradeId) {
        entityManager.clear();
        return tradeRepository.findById(tradeId).orElseThrow();
    }

    private Dispute reloadDispute(long disputeId) {
        entityManager.clear();
        return disputeRepository.findById(disputeId).orElseThrow();
    }

    private PointBalance balanceOf(Member member) {
        entityManager.clear();
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
    }

    private long histories(long tradeId, String type) {
        return entityManager.createQuery("select count(h) from PointHistory h where h.tradeId = :t and h.historyType = negocut.point.entity.PointHistoryType."
                + type, Long.class).setParameter("t", tradeId).getSingleResult();
    }

    private void setTradeField(long tradeId, String field, Object value) {
        Trade trade = reload(tradeId);
        ReflectionTestUtils.setField(trade, field, value);
        tradeRepository.save(trade);
    }

    // 낙찰자가 예약한 포인트(305,000)가 그대로인지 본다.
    private void assertReserved(Member buyer, String reserved, String total) {
        PointBalance balance = balanceOf(buyer);
        assertThat(balance.getReservedPoint()).isEqualByComparingTo(reserved);
        assertThat(balance.getTotalPoint()).isEqualByComparingTo(total);
    }

    // ---- 반환·지급 ----

    @Test
    void 반환으로_판정하면_거래가_취소되고_낙찰자의_예약_포인트가_반환된다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "물건이 도착하지 않았습니다");

        judge(cookieOf(admin), disputeId, "REFUND", "발송 기한 초과가 확인됩니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disputeId").value(disputeId))
                .andExpect(jsonPath("$.data.disputeStatus").value("JUDGED"))
                .andExpect(jsonPath("$.data.judgment").value("REFUND"))
                .andExpect(jsonPath("$.data.tradeId").value(tradeId))
                .andExpect(jsonPath("$.data.tradeStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.judgedAt").isString())
                .andExpect(jsonPath("$.data.dueAt").doesNotExist());

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.CANCELED);
        assertThat(trade.getCompletedAt()).isNotNull();
        assertReserved(buyer, "0", String.valueOf(START_POINT));   // 예약만 풀리고 보유 포인트는 그대로다.
        assertReserved(seller, "0", String.valueOf(START_POINT));
        assertThat(histories(tradeId, "REFUND")).isEqualTo(1);
        assertThat(histories(tradeId, "PAYOUT")).isZero();
        Dispute saved = reloadDispute(disputeId);
        assertThat(saved.getDisputeStatus()).isEqualTo(DisputeStatus.JUDGED);
        assertThat(saved.getJudgment()).isEqualTo(DisputeJudgment.REFUND);
        assertThat(saved.getJudgmentReason()).isEqualTo("발송 기한 초과가 확인됩니다");
        assertThat(saved.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(saved.getJudgedAt()).isNotNull();
    }

    @Test
    void 발송_전에_접수한_분쟁도_반환으로_판정할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        long disputeId = dispute(seller, tradeId, "낙찰자가 배송지를 입력하지 않습니다");   // 준비 상태에서 접수했다.

        judge(cookieOf(admin), disputeId, "REFUND", "배송지 미입력으로 거래를 취소합니다").andExpect(status().isOk());

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.CANCELED);
        assertReserved(buyer, "0", String.valueOf(START_POINT));
    }

    @Test
    void 지급으로_판정하면_거래가_완료되고_판매자에게_포인트가_지급된다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(seller, tradeId, "낙찰자가 수령 확인을 하지 않습니다");

        judge(cookieOf(admin), disputeId, "PAYOUT", "운송장 배송 완료가 확인됩니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disputeStatus").value("JUDGED"))
                .andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"));

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(trade.getCompletedAt()).isNotNull();
        assertReserved(buyer, "0", String.valueOf(START_POINT - PRICE));
        assertReserved(seller, "0", String.valueOf(START_POINT + PRICE));
        assertThat(histories(tradeId, "PAYOUT")).isEqualTo(2);   // 낙찰자·판매자 각각의 지급 이력이다.
        assertThat(histories(tradeId, "REFUND")).isZero();
        assertThat(reloadDispute(disputeId).getJudgment()).isEqualTo(DisputeJudgment.PAYOUT);
    }

    @Test
    void 발송_전에_접수한_분쟁은_지급으로_판정할_수_없고_아무것도_바뀌지_않는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        long disputeId = dispute(buyer, tradeId, "발송 전 분쟁");

        judge(cookieOf(admin), disputeId, "PAYOUT", "발송 전이라 지급할 수 없습니다")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DISPUTE_JUDGMENT_NOT_ALLOWED"));

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(reloadDispute(disputeId).getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));
        assertThat(histories(tradeId, "PAYOUT")).isZero();
    }

    @Test
    void 지급이_실패하면_거래는_분쟁_상태로_남고_포인트는_그대로다() throws Exception {
        Member admin = newAdmin();
        Member seller = createMember(MemberRole.MEMBER, false);   // 포인트 잔액이 없는 판매자라 지급할 수 없다.
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "지급 실패 확인");

        judge(cookieOf(admin), disputeId, "PAYOUT", "지급 실패를 확인합니다").andExpect(status().isInternalServerError());

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(reloadDispute(disputeId).getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(reloadDispute(disputeId).getJudgment()).isNull();
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));   // 낙찰자 쪽 지급도 되돌려졌다.
        assertThat(histories(tradeId, "PAYOUT")).isZero();
    }

    // ---- 거래 진행 ----

    @Test
    void 거래_진행으로_판정하면_접수_직전_상태로_돌아가고_포인트는_그대로이며_다시_접수할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        LocalDateTime originalDue = LocalDateTime.now(ZONE).plusDays(5).withNano(0);
        setTradeField(tradeId, "confirmDueAt", originalDue);
        long disputeId = dispute(buyer, tradeId, "상품 상태를 확인하고 싶습니다");

        // 원래 기한까지 2시간 이상 남았으므로 기한은 그대로다.
        judge(cookieOf(admin), disputeId, "RESUME", "접수 사유가 인정되지 않습니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disputeStatus").value("JUDGED"))
                .andExpect(jsonPath("$.data.judgment").value("RESUME"))
                .andExpect(jsonPath("$.data.tradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.dueType").value("CONFIRM"))
                .andExpect(jsonPath("$.data.dueAt").isString())
                .andExpect(jsonPath("$.data.isDueAdjusted").value(false));

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(trade.getConfirmDueAt()).isEqualTo(originalDue);
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));
        assertThat(histories(tradeId, "REFUND") + histories(tradeId, "PAYOUT")).isZero();
        // 복귀한 거래에는 다시 분쟁을 접수할 수 있다.
        dispute(seller, tradeId, "다시 접수합니다");
    }

    @Test
    void 구매_확정_기한이_2시간_미만이거나_지났으면_복귀_시각부터_2시간으로_정해지고_바로_자동_확정되지_않는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        // 한 거래에서 당사자별로 접수할 수 있는 분쟁은 3회라 구간마다 새 거래를 쓴다.
        for (Duration remaining : new Duration[] {Duration.ofHours(-30), Duration.ofSeconds(-1), Duration.ofMinutes(5), Duration.ofMinutes(119)}) {
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);
            setTradeField(tradeId, "confirmDueAt", LocalDateTime.now(ZONE).plus(remaining));
            long disputeId = dispute(buyer, tradeId, "기한 확인 " + remaining);

            LocalDateTime before = LocalDateTime.now(ZONE);
            judge(cookieOf(admin), disputeId, "RESUME", "복귀 기한 확인 " + remaining)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.isDueAdjusted").value(true));
            LocalDateTime after = LocalDateTime.now(ZONE);

            Trade trade = reload(tradeId);
            assertThat(trade.getConfirmDueAt()).as("%s", remaining).isBetween(before.plusHours(2).minusSeconds(1), after.plusHours(2).plusSeconds(1));
            // 스케줄러가 곧바로 구매 확정을 하지 않는다.
            batchService.autoConfirmDue();
            assertThat(reload(tradeId).getTradeStatus()).as("%s", remaining).isEqualTo(TradeStatus.IN_PROGRESS);
        }
        // 정확히 2시간보다 조금 더 남았으면 원래 기한을 쓴다.
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        LocalDateTime enough = LocalDateTime.now(ZONE).plusHours(2).plusMinutes(2).withNano(0);
        setTradeField(tradeId, "confirmDueAt", enough);
        long disputeId = dispute(buyer, tradeId, "경계 확인");
        judge(cookieOf(admin), disputeId, "RESUME", "경계 확인 복귀").andExpect(status().isOk()).andExpect(jsonPath("$.data.isDueAdjusted").value(false));
        assertThat(reload(tradeId).getConfirmDueAt()).isEqualTo(enough);
    }

    @Test
    void 발송_기한이_지난_준비_거래를_되돌리면_발송_기한이_2시간으로_정해지고_미이행_표시가_지워진다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        setTradeField(tradeId, "shipDueAt", LocalDateTime.now(ZONE).minusHours(1));
        batchService.markUnfulfilledDue();
        assertThat(reload(tradeId).isUnfulfilled()).isTrue();
        long disputeId = dispute(buyer, tradeId, "발송 기한이 지났습니다");

        LocalDateTime before = LocalDateTime.now(ZONE);
        judge(cookieOf(admin), disputeId, "RESUME", "판매자에게 시간을 줍니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.dueType").value("SHIP"))
                .andExpect(jsonPath("$.data.isDueAdjusted").value(true));
        LocalDateTime after = LocalDateTime.now(ZONE);

        Trade trade = reload(tradeId);
        assertThat(trade.getShipDueAt()).isBetween(before.plusHours(2).minusSeconds(1), after.plusHours(2).plusSeconds(1));
        assertThat(trade.isUnfulfilled()).isFalse();
        assertThat(trade.getUnfulfilledAt()).isNull();
        batchService.markUnfulfilledDue();   // 새 기한 전이라 다시 표시되지 않는다.
        assertThat(reload(tradeId).isUnfulfilled()).isFalse();
    }

    // ---- 보류 유지 ----

    @Test
    void 보류_유지는_분쟁과_거래를_그대로_두고_다시_판정할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "추가 확인이 필요합니다");

        judge(cookieOf(admin), disputeId, "HOLD", "택배사에 확인 중입니다")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.judgment").value("HOLD"))
                .andExpect(jsonPath("$.data.tradeStatus").value("DISPUTED"));

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));
        Dispute held = reloadDispute(disputeId);
        assertThat(held.getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(held.getJudgment()).isEqualTo(DisputeJudgment.HOLD);
        assertThat(held.getJudgmentReason()).isEqualTo("택배사에 확인 중입니다");
        // 당사자 화면에는 처리 중이면서 보류 유지로 보이고, 판정 사유는 내려가지 않는다.
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.dispute.disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.dispute.judgment").value("HOLD"))
                .andExpect(jsonPath("$.data.dispute.judgmentReason").doesNotExist());
        // 보류 중에도 다른 분쟁을 접수할 수 없고 거래 상태는 바뀌지 않는다.
        mockMvc.perform(post("/api/trades/" + tradeId + "/disputes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"중복\"}").cookie(cookieOf(seller))).andExpect(status().isConflict());

        // 보류 뒤 다시 판정하면 처리된다.
        judge(cookieOf(admin), disputeId, "REFUND", "확인 결과 미배송입니다").andExpect(status().isOk()).andExpect(jsonPath("$.data.tradeStatus").value("CANCELED"));
        assertThat(reloadDispute(disputeId).getDisputeStatus()).isEqualTo(DisputeStatus.JUDGED);
        assertReserved(buyer, "0", String.valueOf(START_POINT));
    }

    @Test
    void 다른_관리자가_먼저_보류한_분쟁은_낡은_화면의_판정을_거부하고_최신_시각을_확인하면_처리된다() throws Exception {
        Member admin = newAdmin();
        Member other = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "낡은 화면 확인");
        String seenByOther = currentJudgedAt(disputeId);   // 아직 판정이 없다. 두 관리자가 같은 화면을 보고 있다.
        assertThat(seenByOther).isNull();

        judge(cookieOf(admin), disputeId, "HOLD", "택배사에 확인 중입니다", seenByOther).andExpect(status().isOk());

        // 다른 관리자는 보류 사유를 보지 못한 채 판정하려 하므로 거부된다. 같은 보류 유지를 다시 보내도 마찬가지다.
        judge(cookieOf(other), disputeId, "RESUME", "낡은 화면의 복귀", seenByOther)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DISPUTE_CHANGED"));
        judge(cookieOf(other), disputeId, "HOLD", "낡은 화면의 보류", seenByOther)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DISPUTE_CHANGED"));
        judge(cookieOf(other), disputeId, "REFUND", "엉뚱한 시각", "2020-01-01T00:00:00+09:00")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DISPUTE_CHANGED"));
        Dispute held = reloadDispute(disputeId);
        assertThat(held.getJudgment()).isEqualTo(DisputeJudgment.HOLD);
        assertThat(held.getJudgmentReason()).isEqualTo("택배사에 확인 중입니다");   // 거부되면 첫 보류 판정 그대로다.
        assertThat(held.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));

        // 최신 판정 시각을 확인하면 판정할 수 있다.
        judge(cookieOf(other), disputeId, "RESUME", "확인 결과 접수 사유가 인정되지 않습니다", currentJudgedAt(disputeId)).andExpect(status().isOk());
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(reloadDispute(disputeId).getDisputeStatus()).isEqualTo(DisputeStatus.JUDGED);
    }

    @Test
    void 같은_분쟁에_동시에_보류_유지를_보내도_한_건만_성립한다() throws Exception {
        Member admin = newAdmin();
        for (int round = 0; round < 5; round++) {
            Member seller = newMember();
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);
            long disputeId = dispute(buyer, tradeId, "동시 보류 " + round);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String reason = "동시 보류 " + i + " " + System.nanoTime();
                results.add(pool.submit(() -> {
                    go.await();
                    // 모두 아직 판정이 없는 화면을 보고 보냈다.
                    return judge(cookieOf(admin), disputeId, "HOLD", reason, null).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            pool.shutdown();

            assertThat(statuses.stream().filter(s -> s == 200).count()).as("round %d %s", round, statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).as("round %d %s", round, statuses).isEqualTo(3);
            assertThat(reloadDispute(disputeId).getJudgment()).isEqualTo(DisputeJudgment.HOLD);
        }
    }

    @Test
    void 보류_뒤에도_발송_전_분쟁은_지급할_수_없고_진행_분쟁은_거래로_복귀할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long readyTrade = newTrade(seller, buyer);
        long readyDispute = dispute(buyer, readyTrade, "발송 전 보류 확인");
        judge(cookieOf(admin), readyDispute, "HOLD", "보류합니다").andExpect(status().isOk());
        judge(cookieOf(admin), readyDispute, "PAYOUT", "보류 뒤 지급 시도").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPUTE_JUDGMENT_NOT_ALLOWED"));
        assertThat(reloadDispute(readyDispute).getJudgment()).isEqualTo(DisputeJudgment.HOLD);
        assertThat(reloadDispute(readyDispute).getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(reload(readyTrade).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        judge(cookieOf(admin), readyDispute, "RESUME", "보류 뒤 준비 상태로 복귀").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"));
        assertThat(reload(readyTrade).getTradeStatus()).isEqualTo(TradeStatus.READY);
    }

    @Test
    void 분쟁이_처리_중인데_거래가_분쟁_상태가_아니면_판정은_409이고_아무것도_바뀌지_않는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "어긋난 상태 확인");
        setTradeField(tradeId, "tradeStatus", TradeStatus.CANCELED);   // 데이터가 어긋난 경우

        judge(cookieOf(admin), disputeId, "REFUND", "어긋난 상태의 반환").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));

        assertThat(reloadDispute(disputeId).getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(histories(tradeId, "REFUND")).isZero();
        assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));
    }

    @Test
    void 반환이나_지급으로_끝난_거래에는_분쟁을_다시_접수할_수_없다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long refunded = newTrade(seller, buyer);
        ship(refunded, seller, buyer);
        judge(cookieOf(admin), dispute(buyer, refunded, "반환 뒤 재접수"), "REFUND", "반환합니다").andExpect(status().isOk());
        long paid = newTrade(seller, buyer);
        ship(paid, seller, buyer);
        judge(cookieOf(admin), dispute(seller, paid, "지급 뒤 재접수"), "PAYOUT", "지급합니다").andExpect(status().isOk());

        for (long tradeId : new long[] {refunded, paid}) {
            for (Member member : new Member[] {seller, buyer}) {
                mockMvc.perform(post("/api/trades/" + tradeId + "/disputes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"종료된 거래의 재접수\"}").cookie(cookieOf(member)))
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
            }
        }
        assertThat(reload(refunded).getTradeStatus()).isEqualTo(TradeStatus.CANCELED);
        assertThat(reload(paid).getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
    }

    // ---- 예외 ----

    @Test
    void 이미_판정된_분쟁은_다시_판정할_수_없고_포인트는_한_번만_움직인다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long disputeId = dispute(buyer, tradeId, "재판정 확인");

        judge(cookieOf(admin), disputeId, "REFUND", "첫 판정").andExpect(status().isOk());
        // 같은 본문을 바로 다시 보내도 이전 응답을 재생하지 않고 판정이 끝난 분쟁으로 거부한다.
        judge(cookieOf(admin), disputeId, "REFUND", "첫 판정").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DISPUTE_ALREADY_CLOSED"));
        for (String judgment : new String[] {"REFUND", "PAYOUT", "RESUME", "HOLD"}) {
            judge(cookieOf(admin), disputeId, judgment, "다시 판정 " + judgment).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("DISPUTE_ALREADY_CLOSED"));
        }
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.CANCELED);
        assertThat(histories(tradeId, "REFUND")).isEqualTo(1);
        assertThat(histories(tradeId, "PAYOUT")).isZero();
        assertReserved(buyer, "0", String.valueOf(START_POINT));
    }

    @Test
    void 없는_분쟁은_404이고_잘못된_입력은_400이며_권한이_없으면_거부된다() throws Exception {
        Member admin = newAdmin();
        Member member = newMember();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long id = dispute(buyer, tradeId, "검증 확인");
        Cookie cookie = cookieOf(admin);

        judge(cookie, 999999999L, "REFUND", "없는 분쟁").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("DISPUTE_NOT_FOUND"));
        judge(cookie, id, null, "판정 없음").andExpect(status().isBadRequest());
        judge(cookie, id, "NOPE", "없는 판정").andExpect(status().isBadRequest());
        judge(cookie, id, "REFUND", "").andExpect(status().isBadRequest());
        judge(cookie, id, "REFUND", "   ").andExpect(status().isBadRequest());
        judge(cookie, id, "REFUND", "\\u200b\\u3164").andExpect(status().isBadRequest());
        judge(cookie, id, "REFUND", "판정\\u202e사유").andExpect(status().isBadRequest());
        judge(cookie, id, "REFUND", "가".repeat(501)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/disputes/" + id + "/judge").contentType(MediaType.APPLICATION_JSON).content("{}").cookie(cookie))
                .andExpect(status().isBadRequest());
        judge(null, id, "REFUND", "비로그인").andExpect(status().isUnauthorized());
        judge(cookieOf(member), id, "REFUND", "일반 회원").andExpect(status().isForbidden());
        judge(cookieOf(buyer), id, "REFUND", "당사자도 판정할 수 없다").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/disputes")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/disputes").cookie(cookieOf(member))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/trades/" + tradeId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(buyer))).andExpect(status().isForbidden());

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.DISPUTED);
        assertThat(reloadDispute(id).getDisputeStatus()).isEqualTo(DisputeStatus.OPEN);
        judge(cookie, id, "REFUND", "가".repeat(500)).andExpect(status().isOk());   // 경계
    }

    // ---- 목록과 거래 상세 ----

    @Test
    void 분쟁_목록은_최신순이고_상태로_거르며_닉네임을_가리지_않는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long first = newTrade(seller, buyer);
        long second = newTrade(seller, buyer);
        ship(second, seller, buyer);
        long firstDispute = dispute(buyer, first, "목록 확인 1");
        long secondDispute = dispute(seller, second, "목록 확인 2");
        judge(cookieOf(admin), firstDispute, "REFUND", "목록 확인 판정").andExpect(status().isOk());

        // 최근 접수가 먼저고, 접수자는 닉네임과 역할로 보인다.
        mockMvc.perform(get("/api/admin/disputes").param("size", "2").cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].disputeId").value(secondDispute))
                .andExpect(jsonPath("$.data.content[0].tradeId").value(second))
                .andExpect(jsonPath("$.data.content[0].reporterNickname").value(seller.getNickname()))
                .andExpect(jsonPath("$.data.content[0].reporterRole").value("SELLER"))
                .andExpect(jsonPath("$.data.content[0].previousTradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.content[0].tradePrice").value(PRICE))
                .andExpect(jsonPath("$.data.content[0].productName").isString())
                .andExpect(jsonPath("$.data.content[0].reason").value("목록 확인 2"))
                .andExpect(jsonPath("$.data.content[1].disputeId").value(firstDispute))
                .andExpect(jsonPath("$.data.content[1].reporterRole").value("BUYER"))
                .andExpect(jsonPath("$.data.content[1].previousTradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.content[1].disputeStatus").value("JUDGED"))
                .andExpect(jsonPath("$.data.content[1].judgment").value("REFUND"));
        mockMvc.perform(get("/api/admin/disputes").param("status", "OPEN").param("size", "100").cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.content[?(@.disputeId == " + secondDispute + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.content[?(@.disputeId == " + firstDispute + ")]").isEmpty());
        mockMvc.perform(get("/api/admin/disputes").param("status", "NOPE").cookie(cookieOf(admin))).andExpect(status().isBadRequest());
        for (String[] bad : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}}) {
            mockMvc.perform(get("/api/admin/disputes").param(bad[0], bad[1]).cookie(cookieOf(admin))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void 관리자_거래_상세는_닉네임과_배송_정보와_분쟁_이력과_복귀_기한을_담는다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long firstDispute = dispute(buyer, tradeId, "첫 분쟁");
        judge(cookieOf(admin), firstDispute, "RESUME", "첫 분쟁은 인정되지 않습니다").andExpect(status().isOk());
        long secondDispute = dispute(seller, tradeId, "둘째 분쟁");

        mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeId").value(tradeId))
                .andExpect(jsonPath("$.data.tradeStatus").value("DISPUTED"))
                .andExpect(jsonPath("$.data.tradePrice").value(PRICE))
                .andExpect(jsonPath("$.data.sellerNickname").value(seller.getNickname()))
                .andExpect(jsonPath("$.data.buyerNickname").value(buyer.getNickname()))
                .andExpect(jsonPath("$.data.myRole").doesNotExist())
                .andExpect(jsonPath("$.data.availableActions").doesNotExist())
                .andExpect(jsonPath("$.data.auction.productName").isString())
                .andExpect(jsonPath("$.data.schedule.confirmDueAt").isString())
                .andExpect(jsonPath("$.data.delivery.receiverName").value("홍길동"))
                .andExpect(jsonPath("$.data.delivery.address").value("서울 강남구 테헤란로 123"))
                .andExpect(jsonPath("$.data.delivery.trackingNumber").value("1234567890"))
                // 분쟁은 최근 것부터. 처리 중인 분쟁에는 복귀 기한이 있고 판정이 끝난 분쟁에는 없다. 판정 사유는 관리자에게 보인다.
                .andExpect(jsonPath("$.data.disputes.length()").value(2))
                .andExpect(jsonPath("$.data.disputes[0].disputeId").value(secondDispute))
                .andExpect(jsonPath("$.data.disputes[0].disputeStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.disputes[0].reporterRole").value("SELLER"))
                .andExpect(jsonPath("$.data.disputes[0].previousTradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.disputes[0].resumeDue.dueType").value("CONFIRM"))
                .andExpect(jsonPath("$.data.disputes[0].resumeDue.resumedDueAt").isString())
                .andExpect(jsonPath("$.data.disputes[1].disputeId").value(firstDispute))
                .andExpect(jsonPath("$.data.disputes[1].judgment").value("RESUME"))
                .andExpect(jsonPath("$.data.disputes[1].judgmentReason").value("첫 분쟁은 인정되지 않습니다"))
                .andExpect(jsonPath("$.data.disputes[1].resumeDue").doesNotExist());
        mockMvc.perform(get("/api/admin/trades/999999999").cookie(cookieOf(admin))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
    }

    @Test
    void 분쟁이_접수된_적_없는_거래는_관리자_거래_상세에서도_없는_거래와_같이_404다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);   // 배송지와 운송장이 있지만 분쟁이 없는 거래다.

        String noDispute = mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(admin))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get("/api/admin/trades/999999999").cookie(cookieOf(admin))).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(noDispute, "$.error.message")).isEqualTo(JsonPath.<String>read(missing, "$.error.message"));
        assertThat(noDispute).doesNotContain("홍길동").doesNotContain("1234567890");

        // 분쟁이 접수되면 볼 수 있다.
        dispute(buyer, tradeId, "접수 뒤에는 조회");
        mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(admin))).andExpect(status().isOk());
    }

    @Test
    void 복귀_기한_미리보기는_임박한_기한이면_조정되었다고_알린다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        setTradeField(tradeId, "confirmDueAt", LocalDateTime.now(ZONE).plusMinutes(30));
        dispute(buyer, tradeId, "미리보기 확인");

        mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.disputes[0].resumeDue.adjusted").value(true));
        setTradeField(tradeId, "confirmDueAt", LocalDateTime.now(ZONE).plusDays(3));
        mockMvc.perform(get("/api/admin/trades/" + tradeId).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.disputes[0].resumeDue.adjusted").value(false));
    }

    // ---- 동시성 ----

    @Test
    void 같은_분쟁을_동시에_다르게_판정해도_한_건만_성립하고_포인트는_한_번만_움직인다() throws Exception {
        Member admin = newAdmin();
        String[] judgments = {"REFUND", "PAYOUT", "RESUME"};
        for (int round = 0; round < 6; round++) {
            Member seller = newMember();
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);
            long disputeId = dispute(buyer, tradeId, "동시 판정 " + round);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(judgments.length);
            List<Future<Integer>> results = new ArrayList<>();
            for (String judgment : judgments) {
                results.add(pool.submit(() -> {
                    go.await();
                    return judge(cookieOf(admin), disputeId, judgment, "동시 판정 " + judgment + System.nanoTime()).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            pool.shutdown();

            assertThat(statuses.stream().filter(s -> s == 200).count()).as("round %d %s", round, statuses).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).as("round %d %s", round, statuses).isEqualTo(2);
            // 성립한 판정에 맞는 결과만 남는다.
            DisputeJudgment won = reloadDispute(disputeId).getJudgment();
            TradeStatus finalStatus = reload(tradeId).getTradeStatus();
            switch (won) {
                case REFUND -> {
                    assertThat(finalStatus).isEqualTo(TradeStatus.CANCELED);
                    assertReserved(buyer, "0", String.valueOf(START_POINT));
                    assertThat(histories(tradeId, "REFUND")).isEqualTo(1);
                    assertThat(histories(tradeId, "PAYOUT")).isZero();
                }
                case PAYOUT -> {
                    assertThat(finalStatus).isEqualTo(TradeStatus.COMPLETED);
                    assertReserved(buyer, "0", String.valueOf(START_POINT - PRICE));
                    assertReserved(seller, "0", String.valueOf(START_POINT + PRICE));
                    assertThat(histories(tradeId, "PAYOUT")).isEqualTo(2);
                    assertThat(histories(tradeId, "REFUND")).isZero();
                }
                default -> {
                    assertThat(finalStatus).isEqualTo(TradeStatus.IN_PROGRESS);
                    assertReserved(buyer, String.valueOf(PRICE), String.valueOf(START_POINT));
                    assertThat(histories(tradeId, "REFUND") + histories(tradeId, "PAYOUT")).isZero();
                }
            }
        }
    }
}
