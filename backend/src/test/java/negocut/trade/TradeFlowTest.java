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
import negocut.point.entity.PointHistory;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeRepository;
import negocut.trade.service.TradeBatchService;
import negocut.trade.service.TradeConfirmProcessor;

// 거래 조회, 배송지 입력, 발송 등록, 구매 확정과 포인트 지급, 자동 구매 확정·미이행 표시 (기능 명세서 3-8)
// 거래는 실제 경매 흐름(입찰 → 종료 처리)으로 만들어, 낙찰자의 예약 포인트가 지급 대상이 되는 상태에서 시작한다. 낙찰 금액은 305,000원이다.
@SpringBootTest
@AutoConfigureMockMvc
class TradeFlowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final long PRICE = 305_000;
    private static final String ADDRESS = "{\"receiverName\":\"홍길동\",\"receiverPhone\":\"010-1234-5678\",\"zipCode\":\"06236\","
            + "\"address\":\"서울 강남구 테헤란로 123\",\"addressDetail\":\"4층\",\"deliveryMessage\":\"부재 시 경비실\"}";
    private static final String SHIPMENT = "{\"courierName\":\"대한통운\",\"trackingNumber\":\"1234567890\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private TradeConfirmProcessor confirmProcessor;
    @Autowired private TradeBatchService batchService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("tr" + suffix, passwordEncoder.encode("abcd1234"),
                "거래" + suffix.substring(2), "tr" + suffix + "@example.com", "010-1111-2222");
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

    // 판매자 경매에 낙찰자가 입찰해 낙찰되고 거래(준비)가 만들어진 상태. 거래 식별자를 돌려준다.
    private long newTrade(Member seller, Member buyer) throws Exception {
        Category category = categoryRepository.save(Category.create("거래" + TestSuffix.next(), 8400));
        Product product = productRepository.save(Product.create(category, "거래 상품 " + TestSuffix.next(), "설명", "상태 설명"));
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

    private ResultActions putJson(Cookie cookie, String path, String json) throws Exception {
        var request = put(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private ResultActions postJson(Cookie cookie, String path, String json) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private Trade reload(long tradeId) {
        return tradeRepository.findById(tradeId).orElseThrow();
    }

    private PointBalance balanceOf(Member member) {
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
    }

    private List<PointHistory> payouts(Member member, long tradeId) {
        return pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(member.getId()) && h.getHistoryType() == PointHistoryType.PAYOUT
                        && h.getTradeId() != null && h.getTradeId() == tradeId).toList();
    }

    // 거래의 기한을 바꾼다.
    private void setTradeField(long tradeId, String field, Object value) {
        Trade trade = reload(tradeId);
        ReflectionTestUtils.setField(trade, field, value);
        tradeRepository.save(trade);
    }

    // ---- 조회 ----

    @Test
    void 거래는_당사자만_조회하고_역할별_행동_목록과_가려진_상대_닉네임을_보여_준다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId;

        mockMvc.perform(get(path).cookie(cookieOf(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeId").value(tradeId))
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.myRole").value("BUYER"))
                .andExpect(jsonPath("$.data.tradePrice").value(PRICE))
                .andExpect(jsonPath("$.data.counterparty.maskedNickname").value(seller.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.auction.productName").exists())
                .andExpect(jsonPath("$.data.schedule.shipDueAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.schedule.confirmDueAt").value((Object) null))
                .andExpect(jsonPath("$.data.schedule.isUnfulfilled").value(false))
                .andExpect(jsonPath("$.data.delivery").value((Object) null))
                .andExpect(jsonPath("$.data.cancelRequest").value((Object) null))
                .andExpect(jsonPath("$.data.availableActions[0]").value("INPUT_DELIVERY"))
                .andExpect(jsonPath("$.data.availableActions[1]").value("REQUEST_CANCEL"))
                .andExpect(jsonPath("$.data.availableActions[2]").value("OPEN_DISPUTE"))
                .andExpect(jsonPath("$.data.availableActions.length()").value(3));
        // 판매자는 배송지 입력 전에는 취소 요청과 분쟁 접수 말고는 할 수 있는 행동이 없다.
        mockMvc.perform(get(path).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.myRole").value("SELLER"))
                .andExpect(jsonPath("$.data.counterparty.maskedNickname").value(buyer.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.availableActions[0]").value("REQUEST_CANCEL"))
                .andExpect(jsonPath("$.data.availableActions[1]").value("OPEN_DISPUTE"))
                .andExpect(jsonPath("$.data.availableActions.length()").value(2));
        // 당사자가 아니면 403, 없는 거래는 404, 비로그인 401, 관리자 403
        mockMvc.perform(get(path).cookie(cookieOf(newMember()))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        mockMvc.perform(get("/api/trades/999999999").cookie(cookieOf(buyer))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRADE_NOT_FOUND"));
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)))).andExpect(status().isForbidden());
    }

    @Test
    void 내_거래_목록은_역할과_상태로_거르고_최근_거래가_먼저이며_명세의_형식으로_응답한다() throws Exception {
        Member me = newMember();
        Member other = newMember();
        long asBuyer1 = newTrade(other, me);
        long asSeller = newTrade(me, other);
        long asBuyer2 = newTrade(other, me);
        putJson(cookieOf(me), "/api/trades/" + asBuyer2 + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(other), "/api/trades/" + asBuyer2 + "/shipment", SHIPMENT).andExpect(status().isOk());   // asBuyer2는 진행 상태

        mockMvc.perform(get("/api/members/me/trades").cookie(cookieOf(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].tradeId").value(asBuyer2))
                .andExpect(jsonPath("$.data.content[1].tradeId").value(asSeller))
                .andExpect(jsonPath("$.data.content[2].tradeId").value(asBuyer1))
                .andExpect(jsonPath("$.data.content[0].myRole").value("BUYER"))
                .andExpect(jsonPath("$.data.content[1].myRole").value("SELLER"))
                .andExpect(jsonPath("$.data.content[0].tradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.content[0].dueAt").value(org.hamcrest.Matchers.endsWith("+09:00")))   // 진행 중이면 구매 확정 기한
                .andExpect(jsonPath("$.data.content[1].tradePrice").value(PRICE))
                .andExpect(jsonPath("$.data.content[1].isUnfulfilled").value(false))
                .andExpect(jsonPath("$.data.content[1].thumbnailUrl").value((Object) null));
        mockMvc.perform(get("/api/members/me/trades?role=BUYER").cookie(cookieOf(me)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mockMvc.perform(get("/api/members/me/trades?role=SELLER").cookie(cookieOf(me)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].tradeId").value(asSeller));
        mockMvc.perform(get("/api/members/me/trades?status=READY").cookie(cookieOf(me)))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        mockMvc.perform(get("/api/members/me/trades?role=BUYER&status=IN_PROGRESS").cookie(cookieOf(me)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/members/me/trades?size=2&page=1").cookie(cookieOf(me)))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        // 다른 회원의 거래는 보이지 않는다.
        mockMvc.perform(get("/api/members/me/trades").cookie(cookieOf(newMember()))).andExpect(jsonPath("$.data.totalElements").value(0));
        for (String bad : new String[] {"role=ADMIN", "status=UNKNOWN", "size=0", "size=101", "page=-1", "page=2147483647&size=100"}) {
            mockMvc.perform(get("/api/members/me/trades?" + bad).cookie(cookieOf(me))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/members/me/trades")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/members/me/trades").cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL))))
                .andExpect(status().isForbidden());
    }

    // ---- 배송지 입력 ----

    @Test
    void 낙찰자는_준비_상태에서_배송지를_입력하고_발송_전까지_다시_입력해_고칠_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/delivery";

        putJson(cookieOf(buyer), path, ADDRESS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.availableActions[0]").value("INPUT_DELIVERY"));   // 발송 전까지 다시 입력할 수 있다.
        // 판매자는 배송지를 볼 수 있고 발송 등록이 가능해진다. 운송장은 아직 없다.
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.delivery.receiverName").value("홍길동"))
                .andExpect(jsonPath("$.data.delivery.zipCode").value("06236"))
                .andExpect(jsonPath("$.data.delivery.trackingNumber").value((Object) null))
                .andExpect(jsonPath("$.data.availableActions[0]").value("REGISTER_SHIPMENT"));
        // 재입력
        putJson(cookieOf(buyer), path, ADDRESS.replace("홍길동", "김철수").replace("4층", "")).andExpect(status().isOk());
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.delivery.receiverName").value("김철수"))
                .andExpect(jsonPath("$.data.delivery.addressDetail").value((Object) null));
        // 행은 한 건이다.
        assertThat(entityManager.createQuery("select count(d) from TradeDelivery d where d.trade.id = :id", Long.class)
                .setParameter("id", tradeId).getSingleResult()).isEqualTo(1L);
    }

    @Test
    void 배송지_입력은_낙찰자만_하고_형식을_확인하며_발송_뒤에는_고칠_수_없다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/delivery";

        putJson(cookieOf(seller), path, ADDRESS).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        putJson(cookieOf(newMember()), path, ADDRESS).andExpect(status().isForbidden());
        putJson(null, path, ADDRESS).andExpect(status().isUnauthorized());
        putJson(cookieOf(buyer), "/api/trades/999999999/delivery", ADDRESS).andExpect(status().isNotFound());
        for (String bad : new String[] {ADDRESS.replace("\"zipCode\":\"06236\"", "\"zipCode\":\"0623\""),
                ADDRESS.replace("010-1234-5678", "전화번호"), ADDRESS.replace("010-1234-5678", "010--1234-5678"),
                ADDRESS.replace("010-1234-5678", "-0101234567"), ADDRESS.replace("010-1234-5678", "0101234-"), ADDRESS.replace("홍길동", ""), ADDRESS.replace("서울 강남구 테헤란로 123", " "),
                ADDRESS.replace("4층", "가".repeat(256)), "{}"}) {
            putJson(cookieOf(buyer), path, bad).andExpect(status().isBadRequest());
        }

        putJson(cookieOf(buyer), path, ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        // 진행 상태가 되면 배송지를 고칠 수 없다.
        putJson(cookieOf(buyer), path, ADDRESS.replace("홍길동", "박영희")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer))).andExpect(jsonPath("$.data.delivery.receiverName").value("홍길동"));
    }

    // ---- 발송 등록 ----

    @Test
    void 판매자는_배송지가_입력된_뒤_발송을_등록하고_진행_상태와_구매_확정_기한이_정해진다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/shipment";

        // 배송지 입력 전에는 발송할 수 없다.
        postJson(cookieOf(seller), path, SHIPMENT).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), path, SHIPMENT)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.shippedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.confirmDueAt").exists())
                .andExpect(jsonPath("$.data.availableActions[0]").value("REQUEST_CANCEL"))
                .andExpect(jsonPath("$.data.availableActions[1]").value("OPEN_DISPUTE"))
                .andExpect(jsonPath("$.data.availableActions.length()").value(2));

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(trade.getConfirmDueAt()).isNotNull();
        // 구매 확정 기한은 발송 후 10일이다.
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.delivery.courierName").value("대한통운"))
                .andExpect(jsonPath("$.data.delivery.trackingNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.availableActions[0]").value("CONFIRM_PURCHASE"));
        Object shippedAt = entityManager.createQuery("select d.shippedAt from TradeDelivery d where d.trade.id = :id").setParameter("id", tradeId).getSingleResult();
        assertThat(trade.getConfirmDueAt()).isEqualTo(((LocalDateTime) shippedAt).plusDays(10));
        // 이미 진행 상태라 다시 등록할 수 없다.
        postJson(cookieOf(seller), path, SHIPMENT.replace("1234567890", "9999999999")).andExpect(status().isConflict());
    }

    @Test
    void 발송_등록은_판매자만_하고_입력_형식을_확인한다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        String path = "/api/trades/" + tradeId + "/shipment";

        postJson(cookieOf(buyer), path, SHIPMENT).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        postJson(cookieOf(newMember()), path, SHIPMENT).andExpect(status().isForbidden());
        postJson(null, path, SHIPMENT).andExpect(status().isUnauthorized());
        for (String bad : new String[] {"{}", "{\"courierName\":\"\",\"trackingNumber\":\"123\"}", "{\"courierName\":\"택배\",\"trackingNumber\":\"12 34\"}",
                "{\"courierName\":\"택배\",\"trackingNumber\":\"" + "1".repeat(51) + "\"}"}) {
            postJson(cookieOf(seller), path, bad).andExpect(status().isBadRequest());
        }
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.READY);
    }

    // ---- 구매 확정과 포인트 지급 ----

    @Test
    void 낙찰자가_구매를_확정하면_예약_포인트가_판매자의_보유_포인트로_옮겨지고_거래가_완료된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));

        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.confirmedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.completedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.availableActions.length()").value(0));

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(trade.getConfirmedAt()).isNotNull();
        assertThat(trade.getCompletedAt()).isNotNull();
        assertThat(trade.isAutoConfirmed()).isFalse();
        // 낙찰자: 예약과 보유가 함께 줄고, 판매자: 보유가 늘어난다.
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo("0");
        assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
        // 지급 이력은 양쪽에 한 건씩이고 거래를 연결한다.
        List<PointHistory> buyerPayouts = payouts(buyer, tradeId);
        List<PointHistory> sellerPayouts = payouts(seller, tradeId);
        assertThat(buyerPayouts).hasSize(1);
        assertThat(sellerPayouts).hasSize(1);
        assertThat(buyerPayouts.get(0).getAmount()).isEqualByComparingTo(String.valueOf(PRICE));
        assertThat(buyerPayouts.get(0).getTotalPointAfter()).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
        assertThat(buyerPayouts.get(0).getReservedPointAfter()).isEqualByComparingTo("0");
        assertThat(sellerPayouts.get(0).getTotalPointAfter()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
        // 상세에도 완료로 보이고 더 할 수 있는 행동이 없다.
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.availableActions.length()").value(0))
                .andExpect(jsonPath("$.data.schedule.completedAt").exists());
    }

    @Test
    void 구매_확정은_낙찰자만_진행_상태에서_한_번만_할_수_있고_중복_지급은_없다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/confirm";

        // 발송 전(준비)에는 확정할 수 없다.
        postJson(cookieOf(buyer), path, "").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        postJson(cookieOf(seller), path, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        postJson(cookieOf(newMember()), path, "").andExpect(status().isForbidden());
        postJson(null, path, "").andExpect(status().isUnauthorized());
        postJson(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)), path, "").andExpect(status().isForbidden());

        postJson(cookieOf(buyer), path, "").andExpect(status().isOk());
        // 이미 완료된 거래를 다시 확정하면 중복 처리다. 포인트는 한 번만 움직인다.
        postJson(cookieOf(buyer), path + "?retry=1", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_ALREADY_PROCESSED"));
        assertThat(payouts(buyer, tradeId)).hasSize(1);
        assertThat(payouts(seller, tradeId)).hasSize(1);
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
        assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
    }

    @Test
    void 지급이_실패하면_거래는_구매_확정에_멈추고_포인트와_이력은_바뀌지_않으며_다시_지급되지_않는다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        // 낙찰자의 예약 포인트가 모자란 상태(불일치한 데이터)를 만든다. 지급 단계에서 예외가 난다.
        PointBalance buyerBalance = balanceOf(buyer);
        ReflectionTestUtils.setField(buyerBalance, "reservedPoint", BigDecimal.ZERO);
        pointBalanceRepository.save(buyerBalance);

        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedAt").exists())
                .andExpect(jsonPath("$.data.completedAt").value((Object) null));

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.CONFIRMED);
        assertThat(reload(tradeId).getCompletedAt()).isNull();
        assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo("10000000");
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo("10000000");   // 지급과 완료 전환은 한 트랜잭션이라 함께 되돌아간다.
        assertThat(payouts(buyer, tradeId)).isEmpty();
        assertThat(payouts(seller, tradeId)).isEmpty();
        // 멈춘 거래를 다시 확정해도 되살리지 않는다.(2차) 중복 처리다.
        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm?retry=1", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_ALREADY_PROCESSED"));
    }

    @Test
    void 지급에_실패해_구매_확정에_멈춘_거래는_스케줄러가_이어서_지급하고_한_번만_지급된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        // 일시적인 장애로 지급이 실패한 상황: 낙찰자의 예약 포인트를 잠시 0으로 만들어 지급 단계에서 예외가 나게 한다.
        PointBalance buyerBalance = balanceOf(buyer);
        ReflectionTestUtils.setField(buyerBalance, "reservedPoint", BigDecimal.ZERO);
        pointBalanceRepository.save(buyerBalance);
        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andExpect(jsonPath("$.data.tradeStatus").value("CONFIRMED"));
        assertThat(payouts(buyer, tradeId)).isEmpty();

        // 장애가 풀린 상태(예약이 원래대로)에서 재시도하면 이어서 지급되고 완료된다.
        buyerBalance = balanceOf(buyer);
        ReflectionTestUtils.setField(buyerBalance, "reservedPoint", BigDecimal.valueOf(PRICE));
        pointBalanceRepository.save(buyerBalance);
        assertThat(batchService.retryPayouts()).isGreaterThanOrEqualTo(1);

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(reload(tradeId).getCompletedAt()).isNotNull();
        assertThat(payouts(buyer, tradeId)).hasSize(1);
        assertThat(payouts(seller, tradeId)).hasSize(1);
        assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
        // 다시 돌려도 중복 지급이 없다.
        batchService.retryPayouts();
        assertThat(payouts(buyer, tradeId)).hasSize(1);
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
    }

    @Test
    void 같은_거래의_지급이_동시에_겹쳐도_한_번만_지급된다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 6; round++) {
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
            postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
            confirmProcessor.confirmByBuyer(tradeId, buyer.getId());   // 구매 확정만 저장된 상태에서 지급을 동시에 시도한다.

            int threads = 4;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return confirmProcessor.pay(tradeId);
                }));
            }
            ready.await();
            go.countDown();
            long paid = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    paid++;
                }
            }
            pool.shutdown();

            assertThat(paid).as("round %d", round).isEqualTo(1);
            assertThat(payouts(buyer, tradeId)).as("round %d", round).hasSize(1);
            assertThat(payouts(seller, tradeId)).as("round %d", round).hasSize(1);
            assertThat(balanceOf(buyer).getTotalPoint()).as("round %d", round).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
        }
    }

    @Test
    void 거래_제한_회원도_진행_중인_거래를_마무리할_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        // 거래가 만들어진 뒤 양쪽 모두 거래 제한 상태가 된다.
        for (Member member : List.of(seller, buyer)) {
            Member saved = memberRepository.findById(member.getId()).orElseThrow();
            ReflectionTestUtils.setField(saved, "memberStatus", MemberStatus.RESTRICTED_TRADE);
            memberRepository.save(saved);
        }

        putJson(cookieOf(buyer), "/api/trades/" + tradeId + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/shipment", SHIPMENT).andExpect(status().isOk());
        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"));
        mockMvc.perform(get("/api/members/me/trades").cookie(cookieOf(buyer))).andExpect(status().isOk());
    }

    // ---- 시스템 처리 ----

    @Test
    void 발송_후_10일이_지나도_구매_확정이_없으면_자동으로_구매_확정되고_지급된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        Member otherBuyer = newMember();
        long due = newTrade(seller, buyer);
        long notDue = newTrade(seller, otherBuyer);
        long ready = newTrade(seller, newMember());
        putJson(cookieOf(buyer), "/api/trades/" + due + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + due + "/shipment", SHIPMENT).andExpect(status().isOk());
        putJson(cookieOf(otherBuyer), "/api/trades/" + notDue + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + notDue + "/shipment", SHIPMENT).andExpect(status().isOk());
        setTradeField(due, "confirmDueAt", LocalDateTime.now(ZONE).minusMinutes(1));

        assertThat(batchService.autoConfirmDue()).isGreaterThanOrEqualTo(1);

        Trade confirmed = reload(due);
        assertThat(confirmed.getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(confirmed.isAutoConfirmed()).isTrue();
        assertThat(confirmed.getConfirmedAt()).isNotNull();
        assertThat(payouts(buyer, due)).hasSize(1);
        assertThat(payouts(seller, due)).hasSize(1);
        assertThat(reload(notDue).getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);   // 기한 전
        assertThat(reload(ready).getTradeStatus()).isEqualTo(TradeStatus.READY);          // 발송 전
        // 다시 돌려도 중복 지급이 없다.
        batchService.autoConfirmDue();
        assertThat(payouts(buyer, due)).hasSize(1);
        assertThat(balanceOf(seller).getTotalPoint().compareTo(BigDecimal.valueOf(10_000_000 + PRICE))).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 낙찰_후_5일이_지나도_발송이_없으면_미이행으로_표시하고_거래_상태는_바꾸지_않는다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long overdue = newTrade(seller, buyer);
        Member shippedBuyer = newMember();
        long shipped = newTrade(seller, shippedBuyer);
        long notYet = newTrade(seller, newMember());
        putJson(cookieOf(shippedBuyer), "/api/trades/" + shipped + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + shipped + "/shipment", SHIPMENT).andExpect(status().isOk());
        setTradeField(overdue, "shipDueAt", LocalDateTime.now(ZONE).minusMinutes(1));
        setTradeField(shipped, "shipDueAt", LocalDateTime.now(ZONE).minusMinutes(1));

        assertThat(batchService.markUnfulfilledDue()).isGreaterThanOrEqualTo(1);

        Trade marked = reload(overdue);
        assertThat(marked.isUnfulfilled()).isTrue();
        assertThat(marked.getUnfulfilledAt()).isNotNull();
        assertThat(marked.getTradeStatus()).isEqualTo(TradeStatus.READY);   // 상태는 바꾸지 않는다.
        assertThat(reload(shipped).isUnfulfilled()).isFalse();              // 이미 발송했다.
        assertThat(reload(notYet).isUnfulfilled()).isFalse();               // 기한 전
        // 목록과 상세에 미이행 표시가 보이고, 한 번만 표시한다.
        mockMvc.perform(get("/api/trades/" + overdue).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.schedule.isUnfulfilled").value(true))
                .andExpect(jsonPath("$.data.schedule.unfulfilledAt").exists());
        mockMvc.perform(get("/api/members/me/trades?status=READY").cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.content[0].isUnfulfilled").value(true));
        LocalDateTime firstMark = reload(overdue).getUnfulfilledAt();
        batchService.markUnfulfilledDue();
        assertThat(reload(overdue).getUnfulfilledAt()).isEqualTo(firstMark);
        // 미이행 표시가 있어도 발송은 등록할 수 있다.
        putJson(cookieOf(buyer), "/api/trades/" + overdue + "/delivery", ADDRESS).andExpect(status().isOk());
        postJson(cookieOf(seller), "/api/trades/" + overdue + "/shipment", SHIPMENT).andExpect(status().isOk());
    }
}
