package negocut.report;

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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
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
import negocut.report.dto.ReportRequest;
import negocut.report.entity.Report;
import negocut.report.entity.ReportActionType;
import negocut.report.entity.ReportStatus;
import negocut.report.repository.ReportRepository;
import negocut.report.service.ReportService;
import negocut.support.TestSuffix;

// 신고 접수와 내 신고 내역 (기능 명세서 3-9 신고)
@SpringBootTest
@AutoConfigureMockMvc
class ReportTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private ReportService reportService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("rp" + suffix, passwordEncoder.encode("abcd1234"),
                "신고" + suffix.substring(2), "rp" + suffix + "@example.com", "010-1111-2222");
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

    private Auction newAuction(Member seller, String productName) {
        Category category = categoryRepository.save(Category.create("신고" + TestSuffix.next(), 8500));
        Product product = productRepository.save(Product.create(category, productName, "설명", "상태 설명"));
        return auctionRepository.save(Auction.register(product, seller, BigDecimal.valueOf(300_000), BigDecimal.valueOf(5_000),
                LocalDateTime.now(ZONE).plusMinutes(120)));
    }

    // 낙찰되어 거래가 만들어진 상태. 거래 식별자를 돌려준다.
    private long newTrade(Member seller, Member buyer, String productName) throws Exception {
        Auction auction = newAuction(seller, productName);
        mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":305000}").cookie(cookieOf(buyer))).andExpect(status().isOk());
        Auction saved = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "endAt", LocalDateTime.now(ZONE).minusSeconds(5));
        auctionRepository.save(saved);
        assertThat(auctionCloseProcessor.close(auction.getId())).isTrue();
        return ((Number) entityManager.createQuery("select t.id from Trade t where t.auctionResult.auction.id = :id")
                .setParameter("id", auction.getId()).getSingleResult()).longValue();
    }

    private ResultActions report(Cookie cookie, String type, long targetId, String reason, String detail) throws Exception {
        String body = "{\"targetType\":\"" + type + "\",\"targetId\":" + targetId + ",\"reportReason\":\"" + reason + "\""
                + (detail == null ? "" : ",\"detail\":\"" + detail + "\"") + "}";
        return rawPost(cookie, body);
    }

    private ResultActions rawPost(Cookie cookie, String body) throws Exception {
        var request = post("/api/reports").contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private long reportOk(Member reporter, String type, long targetId, String reason, String detail) throws Exception {
        String response = report(cookieOf(reporter), type, targetId, reason, detail)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.reportId")).longValue();
    }

    private Report reload(long reportId) {
        return reportRepository.findById(reportId).orElseThrow();
    }

    // 관리자 처리가 끝난 것처럼 바꾼다. 처리 기능은 관리자 구현에서 만든다.
    private void process(long reportId, ReportStatus status, ReportActionType action) {
        Report saved = reload(reportId);
        Member admin = createMember(MemberRole.ADMIN, MemberStatus.NORMAL);
        if (status == ReportStatus.REJECTED) {
            saved.reject("관리자만 보는 처리 사유", admin, LocalDateTime.now(ZONE));
        } else {
            saved.process(action, "관리자만 보는 처리 사유", admin, LocalDateTime.now(ZONE));
        }
        reportRepository.save(saved);
    }

    // ---- 접수 ----

    @Test
    void 경매_회원_거래를_신고하면_접수_상태로_저장된다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        Member reporter = buyer;   // 거래는 당사자만 신고할 수 있다.
        Auction auction = newAuction(seller, "신고 대상 경매");
        long tradeId = newTrade(seller, buyer, "신고 대상 거래");

        long auctionReport = reportOk(reporter, "AUCTION", auction.getId(), "FALSE_INFO", null);
        long memberReport = reportOk(reporter, "MEMBER", seller.getId(), "TRADE_MISCONDUCT", "물건을 보내지 않습니다");
        long tradeReport = reportOk(reporter, "TRADE", tradeId, "PROHIBITED_ITEM", null);

        Report saved = reload(auctionReport);
        assertThat(saved.getReporter().getId()).isEqualTo(reporter.getId());
        assertThat(saved.getReportStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(saved.getActionType()).isNull();
        assertThat(saved.getProcessedAt()).isNull();
        assertThat(saved.getDetail()).isNull();
        assertThat(reload(memberReport).getDetail()).isEqualTo("물건을 보내지 않습니다");
        assertThat(reload(tradeReport).getTargetId()).isEqualTo(tradeId);
    }

    @Test
    void 거래는_당사자만_신고할_수_있고_비당사자에게는_없는_거래와_같이_404다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        Member outsider = newMember();
        long tradeId = newTrade(seller, buyer, "당사자 신고 거래");

        reportOk(buyer, "TRADE", tradeId, "TRADE_MISCONDUCT", "발송 기한이 지났습니다");
        reportOk(seller, "TRADE", tradeId, "INAPPROPRIATE_OR_ETC", "연락이 되지 않습니다");

        // 비당사자는 거래가 있는지 알 수 없다. 없는 거래와 응답이 같고 신고도 저장되지 않는다.
        String existing = report(cookieOf(outsider), "TRADE", tradeId, "FALSE_INFO", null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("REPORT_TARGET_NOT_FOUND")).andReturn().getResponse().getContentAsString();
        String missing = report(cookieOf(outsider), "TRADE", 999999999L, "FALSE_INFO", null).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(existing, "$.error.message")).isEqualTo(JsonPath.<String>read(missing, "$.error.message"));
        mockMvc.perform(get("/api/members/me/reports").cookie(cookieOf(outsider))).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 처리와_반려는_접수_상태에서만_할_수_있고_중복_검사_키를_비운다() {
        Member reporter = newMember();
        Member admin = createMember(MemberRole.ADMIN, MemberStatus.NORMAL);
        Auction auction = newAuction(newMember(), "처리 메서드 경매");

        Report processed = reportRepository.save(Report.receive(memberRepository.findById(reporter.getId()).orElseThrow(),
                negocut.report.entity.ReportTargetType.AUCTION, auction.getId(), negocut.report.entity.ReportReason.FALSE_INFO, null));
        assertThat(processed.getActiveKey()).isNotNull();
        processed.process(ReportActionType.AUCTION_STOPPED, "사유", admin, LocalDateTime.now(ZONE));
        assertThat(processed.getActiveKey()).isNull();
        assertThat(processed.getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(processed.getActionType()).isEqualTo(ReportActionType.AUCTION_STOPPED);
        assertThat(processed.getAdmin()).isSameAs(admin);
        assertThat(processed.getProcessedAt()).isNotNull();
        // 이미 처리된 신고는 다시 처리하거나 반려할 수 없다.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> processed.process(ReportActionType.MEMBER_RESTRICTED, "사유", admin, LocalDateTime.now(ZONE)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> processed.reject("사유", admin, LocalDateTime.now(ZONE)));

        Report rejected = reportRepository.save(Report.receive(memberRepository.findById(newMember().getId()).orElseThrow(),
                negocut.report.entity.ReportTargetType.AUCTION, auction.getId(), negocut.report.entity.ReportReason.FALSE_INFO, null));
        rejected.reject("사유", admin, LocalDateTime.now(ZONE));
        assertThat(rejected.getActiveKey()).isNull();
        assertThat(rejected.getReportStatus()).isEqualTo(ReportStatus.REJECTED);
        assertThat(rejected.getActionType()).isEqualTo(ReportActionType.REJECTED);
    }

    @Test
    void 본인_경매와_본인_자신은_신고할_수_없다() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller, "내 경매");

        report(cookieOf(seller), "AUCTION", auction.getId(), "FALSE_INFO", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REPORT_SELF_NOT_ALLOWED"));
        report(cookieOf(seller), "MEMBER", seller.getId(), "FALSE_INFO", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REPORT_SELF_NOT_ALLOWED"));
        assertThat(reportRepository.findByReporterId(seller.getId(), org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void 없는_대상_중단된_경매_탈퇴한_회원_관리자는_신고_대상이_아니다() throws Exception {
        Member seller = newMember();
        Member reporter = newMember();
        Auction stopped = newAuction(seller, "중단된 경매");
        Auction saved = auctionRepository.findById(stopped.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "auctionStatus", AuctionStatus.STOPPED);
        auctionRepository.save(saved);
        Member withdrawn = createMember(MemberRole.MEMBER, MemberStatus.WITHDRAWN);
        Member admin = createMember(MemberRole.ADMIN, MemberStatus.NORMAL);

        for (String[] target : new String[][] {{"AUCTION", "999999999"}, {"MEMBER", "999999999"}, {"TRADE", "999999999"},
                {"AUCTION", String.valueOf(stopped.getId())}, {"MEMBER", String.valueOf(withdrawn.getId())},
                {"MEMBER", String.valueOf(admin.getId())}}) {
            report(cookieOf(reporter), target[0], Long.parseLong(target[1]), "FALSE_INFO", null).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REPORT_TARGET_NOT_FOUND"));
        }
    }

    @Test
    void 부적절_행위_기타는_상세_내용이_필수이고_상세는_500자_이하이며_앞뒤_공백을_지운다() throws Exception {
        Member seller = newMember();
        Member reporter = newMember();
        Auction auction = newAuction(seller, "상세 검증 경매");
        long id = auction.getId();

        // 필수: 없음, 빈 문자열, 공백(NBSP·전각·제로폭)뿐인 상세
        for (String detail : new String[] {null, "", "   ", "\\u00a0\\u3000 ", "\\u200b\\ufeff\\u2060", "\\u00ad\\u200e\\u3164\\u2800"}) {
            report(cookieOf(reporter), "AUCTION", id, "INAPPROPRIATE_OR_ETC", detail).andExpect(status().isBadRequest());
        }
        report(cookieOf(reporter), "AUCTION", id, "FALSE_INFO", "가".repeat(501)).andExpect(status().isBadRequest());
        report(cookieOf(reporter), "AUCTION", id, "FALSE_INFO", "\\u00a0" + "가".repeat(501)).andExpect(status().isBadRequest());
        assertThat(reportRepository.existsByActiveKey(Report.activeKeyOf(reporter.getId(), negocut.report.entity.ReportTargetType.AUCTION, id))).isFalse();

        // 다른 사유는 상세를 생략해도 되고, 공백뿐인 상세는 없는 것으로 저장된다.
        long omitted = reportOk(reporter, "AUCTION", id, "FALSE_INFO", "  ");
        assertThat(reload(omitted).getDetail()).isNull();
        long trimmed = reportOk(newMember(), "AUCTION", id, "INAPPROPRIATE_OR_ETC", "\\u00a0 " + "가".repeat(500) + " \\u3000");
        assertThat(reload(trimmed).getDetail()).isEqualTo("가".repeat(500));
    }

    @Test
    void 대상_유형_사유_대상_식별자_검증() throws Exception {
        Member reporter = newMember();

        for (String body : new String[] {
                "{}",
                "{\"targetType\":\"AUCTION\",\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":55}",
                "{\"targetId\":55,\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"BOGUS\",\"targetId\":55,\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":55,\"reportReason\":\"BOGUS\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":0,\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":-3,\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":\"abc\",\"reportReason\":\"FALSE_INFO\"}",
                "{\"targetType\":\"AUCTION\",\"targetId\":99999999999999999999,\"reportReason\":\"FALSE_INFO\"}"}) {
            rawPost(cookieOf(reporter), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        }
    }

    @Test
    void 처리_전에는_같은_대상을_다시_신고할_수_없고_처리_후에는_다시_신고할_수_있다() throws Exception {
        Member seller = newMember();
        Member reporter = newMember();
        Member other = newMember();
        Auction auction = newAuction(seller, "중복 신고 경매");
        Auction another = newAuction(seller, "다른 경매");

        long first = reportOk(reporter, "AUCTION", auction.getId(), "FALSE_INFO", null);
        // 사유를 바꿔 5초 안 같은 본문 재생을 피한다.
        report(cookieOf(reporter), "AUCTION", auction.getId(), "PROHIBITED_ITEM", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REPORT_ALREADY_SUBMITTED"));
        // 다른 대상, 다른 신고자, 같은 번호의 다른 유형은 따로 센다.
        reportOk(reporter, "AUCTION", another.getId(), "FALSE_INFO", null);
        reportOk(other, "AUCTION", auction.getId(), "FALSE_INFO", null);
        reportOk(reporter, "MEMBER", auction.getId() == seller.getId() ? seller.getId() + 1 : seller.getId(), "FALSE_INFO", null);

        process(first, ReportStatus.PROCESSED, ReportActionType.AUCTION_STOPPED);
        long again = reportOk(reporter, "AUCTION", auction.getId(), "PROHIBITED_ITEM", null);
        assertThat(again).isNotEqualTo(first);
        // 반려된 뒤에도 다시 신고할 수 있다.
        process(again, ReportStatus.REJECTED, ReportActionType.REJECTED);
        reportOk(reporter, "AUCTION", auction.getId(), "TRADE_MISCONDUCT", null);
    }

    @Test
    void 같은_대상을_동시에_신고해도_접수는_한_건이다() throws Exception {
        Member seller = newMember();
        Member reporter = newMember();
        Auction auction = newAuction(seller, "동시 신고 경매");
        String[] reasons = {"FALSE_INFO", "PROHIBITED_ITEM", "TRADE_MISCONDUCT", "INAPPROPRIATE_OR_ETC"};

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(reasons.length);
        List<Future<Integer>> results = new ArrayList<>();
        for (String reason : reasons) {
            results.add(pool.submit(() -> {
                go.await();
                // 본문이 모두 달라야 중복 요청 방지 필터의 재생이 아니라 서비스와 유니크 제약이 막는 경로를 탄다.
                return report(cookieOf(reporter), "AUCTION", auction.getId(), reason, "동시 접수 " + reason).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).as("%s", statuses).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as("%s", statuses).isEqualTo(reasons.length - 1);
        long rows = entityManager.createQuery("select count(r) from Report r where r.reporter.id = :id", Long.class)
                .setParameter("id", reporter.getId()).getSingleResult();
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void 서비스를_직접_동시에_불러도_접수는_한_건이고_나머지는_중복_접수_오류다() throws Exception {
        Member seller = newMember();
        Member reporter = newMember();
        Auction auction = newAuction(seller, "서비스 동시 신고 경매");
        int threads = 6;

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    reportService.receive(reporter.getId(), new ReportRequest(negocut.report.entity.ReportTargetType.AUCTION,
                            auction.getId(), negocut.report.entity.ReportReason.FALSE_INFO, null));
                    return "OK";
                } catch (negocut.common.exception.BusinessException e) {
                    return e.getErrorCode().name();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }
        pool.shutdown();

        assertThat(outcomes.stream().filter("OK"::equals).count()).as("%s", outcomes).isEqualTo(1);
        assertThat(outcomes.stream().filter("REPORT_ALREADY_SUBMITTED"::equals).count()).as("%s", outcomes).isEqualTo(threads - 1);
    }

    @Test
    void 인증_권한_거래_제한_회원() throws Exception {
        Member seller = newMember();
        Auction auction = newAuction(seller, "권한 확인 경매");

        report(null, "AUCTION", auction.getId(), "FALSE_INFO", null).andExpect(status().isUnauthorized());
        report(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL)), "AUCTION", auction.getId(), "FALSE_INFO", null)
                .andExpect(status().isForbidden());
        // 거래 제한 회원도 신고할 수 있다.
        Member restricted = createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE);
        reportOk(restricted, "AUCTION", auction.getId(), "FALSE_INFO", null);
    }

    // ---- 내 신고 내역 ----

    @Test
    void 내_신고_내역은_본인_신고만_최근순으로_대상_표시와_처리_결과를_보여주고_처리_사유와_처리자는_숨긴다() throws Exception {
        Member seller = newMember();
        Member buyer = newMember();
        Member reporter = buyer;   // 거래는 당사자만 신고할 수 있다.
        Member someoneElse = newMember();
        Auction auction = newAuction(seller, "라벨 경매 상품");
        long tradeId = newTrade(seller, buyer, "라벨 거래 상품");

        long a = reportOk(reporter, "AUCTION", auction.getId(), "FALSE_INFO", null);
        long m = reportOk(reporter, "MEMBER", seller.getId(), "TRADE_MISCONDUCT", null);
        long t = reportOk(reporter, "TRADE", tradeId, "PROHIBITED_ITEM", null);
        reportOk(someoneElse, "AUCTION", auction.getId(), "FALSE_INFO", null);
        process(a, ReportStatus.PROCESSED, ReportActionType.AUCTION_STOPPED);

        String body = mockMvc.perform(get("/api/members/me/reports").cookie(cookieOf(reporter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                // 최근 신고가 먼저다.
                .andExpect(jsonPath("$.data.content[0].reportId").value(t))
                .andExpect(jsonPath("$.data.content[0].targetType").value("TRADE"))
                .andExpect(jsonPath("$.data.content[0].targetLabel").value("라벨 거래 상품"))
                .andExpect(jsonPath("$.data.content[0].reportStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.data.content[0].actionType").value((Object) null))
                .andExpect(jsonPath("$.data.content[0].processedAt").value((Object) null))
                .andExpect(jsonPath("$.data.content[1].reportId").value(m))
                .andExpect(jsonPath("$.data.content[1].targetLabel").value(seller.getNickname().substring(0, 2) + "***"))
                .andExpect(jsonPath("$.data.content[2].reportId").value(a))
                .andExpect(jsonPath("$.data.content[2].targetLabel").value("라벨 경매 상품"))
                .andExpect(jsonPath("$.data.content[2].reportStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.content[2].actionType").value("AUCTION_STOPPED"))
                .andExpect(jsonPath("$.data.content[2].processedAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.content[2].createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        // 처리 사유·처리자·신고자·상세는 응답에 없다.
        assertThat(body).doesNotContain("관리자만 보는 처리 사유").doesNotContain("actionReason").doesNotContain("adminId")
                .doesNotContain("reporter").doesNotContain("detail");

        // 다른 회원의 목록에는 내 신고가 없다.
        mockMvc.perform(get("/api/members/me/reports").cookie(cookieOf(someoneElse)))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].targetLabel").value("라벨 경매 상품"));
        // 페이지 나누기
        mockMvc.perform(get("/api/members/me/reports").param("page", "1").param("size", "2").cookie(cookieOf(reporter)))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void 내_신고_내역_입력_검증과_권한() throws Exception {
        Member member = newMember();

        for (String[] params : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}}) {
            mockMvc.perform(get("/api/members/me/reports").param(params[0], params[1]).cookie(cookieOf(member)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/members/me/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/members/me/reports").cookie(cookieOf(createMember(MemberRole.ADMIN, MemberStatus.NORMAL))))
                .andExpect(status().isForbidden());
        // 거래 제한 회원은 조회할 수 있다.
        mockMvc.perform(get("/api/members/me/reports").cookie(cookieOf(createMember(MemberRole.MEMBER, MemberStatus.RESTRICTED_TRADE))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
