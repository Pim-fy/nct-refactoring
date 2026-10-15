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
import negocut.point.entity.PointHistory;
import negocut.point.entity.PointHistoryType;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.repository.PointHistoryRepository;
import negocut.support.TestSuffix;
import negocut.trade.entity.CancelRequestStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeCancelRequest;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeCancelRequestRepository;
import negocut.trade.repository.TradeRepository;
import negocut.trade.service.TradeBatchService;

// 거래 취소 요청·수락·거절과 포인트 반환, 구매 확정 시 열린 요청 종결 (기능 명세서 3-8 거래 취소)
// 거래는 실제 경매 흐름(입찰 → 종료 처리)으로 만들어 낙찰자의 예약 포인트(305,000원)가 묶인 상태에서 시작한다.
@SpringBootTest
@AutoConfigureMockMvc
class TradeCancelTest {

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
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private TradeBatchService batchService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("tc" + suffix, passwordEncoder.encode("abcd1234"),
                "취소" + suffix.substring(2), "tc" + suffix + "@example.com", "010-1111-2222");
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
        Category category = categoryRepository.save(Category.create("취소" + TestSuffix.next(), 8300));
        Product product = productRepository.save(Product.create(category, "취소 상품 " + TestSuffix.next(), "설명", "상태 설명"));
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

    private long requestCancel(Cookie cookie, long tradeId, String reason) throws Exception {
        String response = postJson(cookie, "/api/trades/" + tradeId + "/cancel-requests", "{\"reason\":\"" + reason + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestStatus").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.requestId")).longValue();
    }

    private ResultActions respond(Cookie cookie, long tradeId, long requestId, String action) throws Exception {
        return postJson(cookie, "/api/trades/" + tradeId + "/cancel-requests/" + requestId + "/" + action, "");
    }

    private Trade reload(long tradeId) {
        return tradeRepository.findById(tradeId).orElseThrow();
    }

    private TradeCancelRequest reloadRequest(long requestId) {
        return cancelRequestRepository.findById(requestId).orElseThrow();
    }

    private PointBalance balanceOf(Member member) {
        return pointBalanceRepository.findByMemberId(member.getId()).orElseThrow();
    }

    private List<PointHistory> historyOf(Member member, PointHistoryType type, long tradeId) {
        return pointHistoryRepository.findAll().stream()
                .filter(h -> h.getMember().getId().equals(member.getId()) && h.getHistoryType() == type
                        && h.getTradeId() != null && h.getTradeId() == tradeId).toList();
    }

    // ---- 요청 ----

    @Test
    void 판매자와_낙찰자는_준비_상태에서_취소를_요청할_수_있고_상대방에게_수락_거절_행동이_생긴다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId;

        // 요청 전에는 양쪽 모두 취소를 요청할 수 있다.
        mockMvc.perform(get(path).cookie(cookieOf(seller))).andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").exists());
        mockMvc.perform(get(path).cookie(cookieOf(buyer))).andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").exists());

        long requestId = requestCancel(cookieOf(buyer), tradeId, "마음이 바뀌어 취소하고 싶습니다");

        TradeCancelRequest saved = reloadRequest(requestId);
        assertThat(saved.getRequestStatus()).isEqualTo(CancelRequestStatus.REQUESTED);
        assertThat(saved.getRespondedAt()).isNull();
        // 요청자는 더 할 수 있는 취소 행동이 없고 상대방은 수락·거절할 수 있다. 거래 상태는 그대로다.
        mockMvc.perform(get(path).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.tradeStatus").value("READY"))
                .andExpect(jsonPath("$.data.cancelRequest.requestId").value(requestId))
                .andExpect(jsonPath("$.data.cancelRequest.requesterRole").value("BUYER"))
                .andExpect(jsonPath("$.data.cancelRequest.reason").value("마음이 바뀌어 취소하고 싶습니다"))
                .andExpect(jsonPath("$.data.cancelRequest.requestStatus").value("REQUESTED"))
                .andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").doesNotExist())
                .andExpect(jsonPath("$.data.availableActions[?(@=='ACCEPT_CANCEL')]").doesNotExist());
        mockMvc.perform(get(path).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.cancelRequest.requesterRole").value("BUYER"))
                .andExpect(jsonPath("$.data.availableActions[?(@=='ACCEPT_CANCEL')]").exists())
                .andExpect(jsonPath("$.data.availableActions[?(@=='REJECT_CANCEL')]").exists())
                .andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").doesNotExist());
        // 진행 상태에서도 판매자가 요청할 수 있다.
        Member seller2 = newMember();
        Member buyer2 = newMember();
        long inProgress = newTrade(seller2, buyer2);
        ship(inProgress, seller2, buyer2);
        requestCancel(cookieOf(seller2), inProgress, "재고가 없습니다");
    }

    @Test
    void 처리_중인_요청이_있으면_다시_요청할_수_없고_사유와_권한을_확인한다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/cancel-requests";
        requestCancel(cookieOf(buyer), tradeId, "사유 1");

        postJson(cookieOf(buyer), path, "{\"reason\":\"사유 2\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_ALREADY_REQUESTED"));
        // 상대방도 처리 중인 요청이 있는 동안에는 새로 요청할 수 없다.
        postJson(cookieOf(seller), path, "{\"reason\":\"사유 3\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_ALREADY_REQUESTED"));
        // NBSP·전각 공백만 있는 사유와 앞뒤 공백을 지운 뒤 500자를 넘는 사유도 거부한다.
        for (String bad : new String[] {"{}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}", "{\"reason\":\"\\u00a0\\u3000 \"}",
                "{\"reason\":\"" + "가".repeat(501) + "\"}", "{\"reason\":\"\\u00a0" + "가".repeat(501) + "\"}"}) {
            postJson(cookieOf(seller), path, bad).andExpect(status().isBadRequest());
        }
        postJson(cookieOf(newMember()), path, "{\"reason\":\"남\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));
        postJson(null, path, "{\"reason\":\"x\"}").andExpect(status().isUnauthorized());
        postJson(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)), path, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        postJson(cookieOf(buyer), "/api/trades/999999999/cancel-requests", "{\"reason\":\"x\"}").andExpect(status().isNotFound());
    }

    @Test
    void 거절된_직후_같은_사유로_다시_요청하면_이전_응답이_재생되지_않고_새_요청이_만들어진다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);

        long first = requestCancel(cookieOf(buyer), tradeId, "같은 사유입니다");
        respond(cookieOf(seller), tradeId, first, "reject").andExpect(status().isOk());
        long second = requestCancel(cookieOf(buyer), tradeId, "같은 사유입니다");   // 5초 안에 같은 본문

        assertThat(second).isNotEqualTo(first);
        assertThat(reloadRequest(second).getRequestStatus()).isEqualTo(CancelRequestStatus.REQUESTED);
        assertThat(reloadRequest(first).getRequestStatus()).isEqualTo(CancelRequestStatus.REJECTED);
    }

    @Test
    void 구매_확정_완료_취소된_거래는_취소를_요청할_수_없다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"));
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/cancel-requests", "{\"reason\":\"늦었지만\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));

        Member seller2 = newMember();
        Member buyer2 = newMember();
        long canceled = newTrade(seller2, buyer2);
        long requestId = requestCancel(cookieOf(seller2), canceled, "취소 부탁");
        respond(cookieOf(buyer2), canceled, requestId, "accept").andExpect(status().isOk());
        postJson(cookieOf(buyer2), "/api/trades/" + canceled + "/cancel-requests", "{\"reason\":\"또\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_STATUS_INVALID"));
        mockMvc.perform(get("/api/trades/" + canceled).cookie(cookieOf(buyer2))).andExpect(jsonPath("$.data.availableActions.length()").value(0));
    }

    @Test
    void 취소_요청은_거절된_것도_세어_당사자별로_3회까지만_낼_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        String path = "/api/trades/" + tradeId + "/cancel-requests";

        for (int i = 1; i <= 3; i++) {
            long requestId = requestCancel(cookieOf(buyer), tradeId, "요청 " + i);
            respond(cookieOf(seller), tradeId, requestId, "reject").andExpect(status().isOk());
        }
        // 낙찰자는 4번째를 낼 수 없고 행동 목록에서도 빠진다.
        postJson(cookieOf(buyer), path, "{\"reason\":\"요청 4\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_LIMIT_EXCEEDED"));
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").doesNotExist());
        // 판매자의 횟수는 따로 센다.
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(seller)))
                .andExpect(jsonPath("$.data.availableActions[?(@=='REQUEST_CANCEL')]").exists());
        requestCancel(cookieOf(seller), tradeId, "판매자 요청");
    }

    // ---- 수락 ----

    @Test
    void 낙찰자의_요청을_판매자가_수락하면_거래가_취소되고_낙찰자의_예약_포인트가_반환된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long requestId = requestCancel(cookieOf(buyer), tradeId, "구성품이 설명과 다릅니다");
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));

        // 판매자가 수락하면 응답의 point는 null이다.
        respond(cookieOf(seller), tradeId, requestId, "accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.requestStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.point").value((Object) null));

        Trade trade = reload(tradeId);
        assertThat(trade.getTradeStatus()).isEqualTo(TradeStatus.CANCELED);
        assertThat(trade.getCompletedAt()).isNotNull();
        assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.ACCEPTED);
        assertThat(reloadRequest(requestId).getRespondedAt()).isNotNull();
        // 예약만 풀리고 보유 포인트는 그대로이며, 판매자의 포인트는 바뀌지 않는다.
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo("0");
        assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo("10000000");
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo("10000000");
        List<PointHistory> refunds = historyOf(buyer, PointHistoryType.REFUND, tradeId);
        assertThat(refunds).hasSize(1);
        assertThat(refunds.get(0).getAmount()).isEqualByComparingTo(String.valueOf(PRICE));
        assertThat(refunds.get(0).getReservedPointAfter()).isEqualByComparingTo("0");
        assertThat(historyOf(seller, PointHistoryType.REFUND, tradeId)).isEmpty();
        assertThat(historyOf(buyer, PointHistoryType.PAYOUT, tradeId)).isEmpty();
        // 이미 처리된 요청에 다시 응답하면 거부하고 포인트는 한 번만 돌려준다. (같은 요청을 5초 안에 되풀이하면 중복 요청 방지가 첫 응답을 재생하므로 주소를 바꿔 보낸다.)
        postJson(cookieOf(seller), "/api/trades/" + tradeId + "/cancel-requests/" + requestId + "/accept?retry=1", "")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_REQUEST_CLOSED"));
        respond(cookieOf(seller), tradeId, requestId, "reject").andExpect(status().isConflict());
        assertThat(historyOf(buyer, PointHistoryType.REFUND, tradeId)).hasSize(1);
        // 취소된 거래는 구매 확정도 할 수 없다.
        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andExpect(status().isConflict());
    }

    @Test
    void 판매자의_요청을_낙찰자가_수락하면_응답에_갱신된_포인트가_담긴다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        long requestId = requestCancel(cookieOf(seller), tradeId, "물건이 파손되었습니다");

        respond(cookieOf(buyer), tradeId, requestId, "accept")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.point.totalPoint").value(10000000))
                .andExpect(jsonPath("$.data.point.reservedPoint").value(0))
                .andExpect(jsonPath("$.data.point.availablePoint").value(10000000));
    }

    // ---- 거절 ----

    @Test
    void 거절하면_요청이_종료되고_거래는_원래_상태로_남으며_다시_요청할_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long requestId = requestCancel(cookieOf(seller), tradeId, "취소해 주세요");

        respond(cookieOf(buyer), tradeId, requestId, "reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.requestStatus").value("REJECTED"));

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.IN_PROGRESS);
        assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.REJECTED);
        assertThat(balanceOf(buyer).getReservedPoint()).isEqualByComparingTo(String.valueOf(PRICE));   // 포인트는 그대로
        assertThat(historyOf(buyer, PointHistoryType.REFUND, tradeId)).isEmpty();
        mockMvc.perform(get("/api/trades/" + tradeId).cookie(cookieOf(buyer)))
                .andExpect(jsonPath("$.data.cancelRequest").value((Object) null))
                .andExpect(jsonPath("$.data.availableActions[?(@=='CONFIRM_PURCHASE')]").exists());
        respond(cookieOf(buyer), tradeId, requestId, "accept").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_REQUEST_CLOSED"));
        requestCancel(cookieOf(seller), tradeId, "다시 요청합니다");
    }

    @Test
    void 응답은_상대방만_하고_요청자나_당사자가_아닌_회원과_없는_요청은_거부한다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        long requestId = requestCancel(cookieOf(buyer), tradeId, "취소 요청");

        for (String action : new String[] {"accept", "reject"}) {
            respond(cookieOf(buyer), tradeId, requestId, action).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TRADE_ACCESS_DENIED"));   // 자기 요청에는 응답할 수 없다.
            respond(cookieOf(newMember()), tradeId, requestId, action).andExpect(status().isForbidden());
            respond(null, tradeId, requestId, action).andExpect(status().isUnauthorized());
            respond(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)), tradeId, requestId, action).andExpect(status().isForbidden());
            respond(cookieOf(seller), tradeId, 999_999_999L, action).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_REQUEST_NOT_FOUND"));
        }
        // 다른 거래의 요청 식별자로는 응답할 수 없다.
        Member otherSeller = newMember();
        Member otherBuyer = newMember();
        long otherTrade = newTrade(otherSeller, otherBuyer);
        respond(cookieOf(otherSeller), otherTrade, requestId, "accept").andExpect(status().isNotFound());
        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.READY);
        assertThat(reload(otherTrade).getTradeStatus()).isEqualTo(TradeStatus.READY);
    }

    @Test
    void 거래_제한_회원도_취소_요청과_응답을_할_수_있다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        for (Member member : List.of(seller, buyer)) {
            Member saved = memberRepository.findById(member.getId()).orElseThrow();
            ReflectionTestUtils.setField(saved, "memberStatus", MemberStatus.RESTRICTED_TRADE);
            memberRepository.save(saved);
        }

        long requestId = requestCancel(cookieOf(buyer), tradeId, "제한된 상태에서 요청");
        respond(cookieOf(seller), tradeId, requestId, "accept").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeStatus").value("CANCELED"));
    }

    // ---- 열린 요청 종결과 경쟁 ----

    @Test
    void 구매_확정으로_거래가_바뀌면_처리_중인_취소_요청은_종결로_닫히고_이후_수락은_거부된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long requestId = requestCancel(cookieOf(seller), tradeId, "곧 취소하고 싶습니다");

        postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andExpect(jsonPath("$.data.tradeStatus").value("COMPLETED"));

        assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.CLOSED);
        assertThat(reloadRequest(requestId).getRespondedAt()).isNotNull();
        respond(cookieOf(buyer), tradeId, requestId, "accept").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRADE_CANCEL_REQUEST_CLOSED"));
        // 포인트는 지급만 되고 반환되지 않는다.
        assertThat(historyOf(buyer, PointHistoryType.REFUND, tradeId)).isEmpty();
        assertThat(historyOf(buyer, PointHistoryType.PAYOUT, tradeId)).hasSize(1);
        assertThat(balanceOf(seller).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 + PRICE));
    }

    @Test
    void 자동_구매_확정으로_거래가_바뀌어도_처리_중인_취소_요청은_종결로_닫힌다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);
        ship(tradeId, seller, buyer);
        long requestId = requestCancel(cookieOf(buyer), tradeId, "확정 전에 요청");
        Trade trade = reload(tradeId);
        ReflectionTestUtils.setField(trade, "confirmDueAt", LocalDateTime.now(ZONE).minusMinutes(1));
        tradeRepository.save(trade);

        assertThat(batchService.autoConfirmDue()).isGreaterThanOrEqualTo(1);

        assertThat(reload(tradeId).getTradeStatus()).isEqualTo(TradeStatus.COMPLETED);
        assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.CLOSED);
        respond(cookieOf(seller), tradeId, requestId, "accept").andExpect(status().isConflict());
        assertThat(historyOf(buyer, PointHistoryType.REFUND, tradeId)).isEmpty();
    }

    @Test
    void 수락과_구매_확정이_동시에_겹쳐도_하나만_성립하고_포인트가_이중으로_움직이지_않는다() throws Exception {
        Member seller = newMember();
        for (int round = 0; round < 8; round++) {
            Member buyer = newMember();
            long tradeId = newTrade(seller, buyer);
            ship(tradeId, seller, buyer);
            long requestId = requestCancel(cookieOf(seller), tradeId, "동시 처리 확인 " + round);

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> accept = pool.submit(() -> {
                go.await();
                return respond(cookieOf(buyer), tradeId, requestId, "accept").andReturn().getResponse().getStatus();
            });
            Future<Integer> confirm = pool.submit(() -> {
                go.await();
                return postJson(cookieOf(buyer), "/api/trades/" + tradeId + "/confirm", "").andReturn().getResponse().getStatus();
            });
            go.countDown();
            int acceptStatus = accept.get();
            int confirmStatus = confirm.get();
            pool.shutdown();

            TradeStatus finalStatus = reload(tradeId).getTradeStatus();
            int refunds = historyOf(buyer, PointHistoryType.REFUND, tradeId).size();
            int payouts = historyOf(buyer, PointHistoryType.PAYOUT, tradeId).size();
            assertThat(finalStatus).as("round %d: 수락 %d, 확정 %d", round, acceptStatus, confirmStatus)
                    .isIn(TradeStatus.CANCELED, TradeStatus.COMPLETED);
            assertThat(refunds + payouts).as("round %d: 반환 %d, 지급 %d", round, refunds, payouts).isEqualTo(1);   // 이중 처리 없음
            assertThat(balanceOf(buyer).getReservedPoint()).as("round %d", round).isEqualByComparingTo("0");
            if (finalStatus == TradeStatus.CANCELED) {
                assertThat(acceptStatus).isEqualTo(200);
                assertThat(confirmStatus).isEqualTo(409);
                assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo("10000000");
                assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.ACCEPTED);
            } else {
                assertThat(confirmStatus).isEqualTo(200);
                assertThat(acceptStatus).isEqualTo(409);
                assertThat(balanceOf(buyer).getTotalPoint()).isEqualByComparingTo(String.valueOf(10_000_000 - PRICE));
                assertThat(reloadRequest(requestId).getRequestStatus()).isEqualTo(CancelRequestStatus.CLOSED);
            }
        }
    }

    @Test
    void 같은_거래에_양쪽이_동시에_취소를_요청해도_처리_중인_요청은_한_건이다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (Member member : List.of(seller, buyer)) {
            results.add(pool.submit(() -> {
                go.await();
                return postJson(cookieOf(member), "/api/trades/" + tradeId + "/cancel-requests",
                        "{\"reason\":\"동시 요청 " + member.getId() + "\"}").andReturn().getResponse().getStatus();
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
                "select count(r) from TradeCancelRequest r where r.trade.id = :id and r.requestStatus = negocut.trade.entity.CancelRequestStatus.REQUESTED",
                Long.class).setParameter("id", tradeId).getSingleResult();
        assertThat(processing).isEqualTo(1);
    }
}
