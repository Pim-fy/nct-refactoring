package negocut.admin;

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
import java.util.concurrent.TimeUnit;

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
import negocut.report.entity.Report;
import negocut.report.entity.ReportActionType;
import negocut.report.entity.ReportStatus;
import negocut.report.repository.ReportRepository;
import negocut.support.TestSuffix;

// 관리자 신고 관리: 신고 목록과 신고 처리 (기능 명세서 3-10 신고 처리)
@SpringBootTest
@AutoConfigureMockMvc
class AdminReportTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String RESTRICT_TRADE = "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":true}";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuctionRepository auctionRepository;
    @Autowired private ReportRepository reportRepository;
    @Autowired private AuctionCloseProcessor auctionCloseProcessor;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("ar" + suffix, passwordEncoder.encode("abcd1234"),
                "처리" + suffix.substring(2), "ar" + suffix + "@example.com", "010-1111-2222");
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

    private Member newAdmin() {
        return createMember(MemberRole.ADMIN, MemberStatus.NORMAL);
    }

    private Cookie cookieOf(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private Auction newAuction(Member seller, String productName) {
        Category category = categoryRepository.save(Category.create("처리" + TestSuffix.next(), 8700));
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

    // 접수 상태의 신고를 저장소로 직접 만든다.
    private long newReport(Member reporter, String targetType, long targetId) {
        return reportRepository.save(Report.receive(reporter, negocut.report.entity.ReportTargetType.valueOf(targetType), targetId,
                negocut.report.entity.ReportReason.FALSE_INFO, "관리자가 처리할 신고")).getId();
    }

    private ResultActions process(Cookie cookie, long reportId, String body) throws Exception {
        var request = post("/api/admin/reports/" + reportId + "/process").contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private static String body(String actionType, String reason, String restriction) {
        return "{\"actionType\":\"" + actionType + "\",\"actionReason\":\"" + reason + "\""
                + (restriction == null ? "" : ",\"restriction\":" + restriction) + "}";
    }

    private Report reload(long reportId) {
        entityManager.clear();
        return reportRepository.findById(reportId).orElseThrow();
    }

    private Member reload(Member member) {
        entityManager.clear();
        return memberRepository.findById(member.getId()).orElseThrow();
    }

    private Auction reload(Auction auction) {
        entityManager.clear();
        return auctionRepository.findById(auction.getId()).orElseThrow();
    }

    private long restrictionCount(Member member) {
        return entityManager.createQuery("select count(r) from MemberRestriction r where r.member.id = :m", Long.class)
                .setParameter("m", member.getId()).getSingleResult();
    }

    private void setAuctionStatus(Auction auction, AuctionStatus status) {
        Auction saved = auctionRepository.findById(auction.getId()).orElseThrow();
        ReflectionTestUtils.setField(saved, "auctionStatus", status);
        auctionRepository.save(saved);
    }

    // 신고가 처리 전 그대로인지 확인한다.
    private void assertUntouched(long reportId) {
        Report report = reload(reportId);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.RECEIVED);
        assertThat(report.getActionType()).isNull();
        assertThat(report.getActiveKey()).isNotNull();
    }

    // ---- 경매 중단 ----

    @Test
    void 경매_신고를_경매_중단으로_처리하면_경매가_중단되고_신고가_처리_완료가_된다() throws Exception {
        Member admin = newAdmin();
        Member reporter = newMember();
        Auction auction = newAuction(newMember(), "금지 물품 경매");
        long reportId = newReport(reporter, "AUCTION", auction.getId());

        process(cookieOf(admin), reportId, body("AUCTION_STOPPED", "금지 물품에 해당합니다", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value(reportId))
                .andExpect(jsonPath("$.data.reportStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.actionType").value("AUCTION_STOPPED"))
                .andExpect(jsonPath("$.data.appliedAction.auctionId").value(auction.getId()))
                .andExpect(jsonPath("$.data.appliedAction.auctionStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.appliedAction.isAlreadyApplied").value(false));

        Auction savedAuction = reload(auction);
        assertThat(savedAuction.getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);
        assertThat(savedAuction.getStoppedReason()).isEqualTo("금지 물품에 해당합니다");
        assertThat(savedAuction.getStoppedByAdminId()).isEqualTo(admin.getId());
        Report report = reload(reportId);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(report.getActionType()).isEqualTo(ReportActionType.AUCTION_STOPPED);
        assertThat(report.getActionReason()).isEqualTo("금지 물품에 해당합니다");
        assertThat(report.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(report.getProcessedAt()).isNotNull();
        assertThat(report.getActiveKey()).isNull();   // 같은 대상을 다시 신고할 수 있다.
    }

    @Test
    void 입찰이_있는_경매를_신고로_중단하면_최고_입찰자의_예약이_풀린다() throws Exception {
        Member admin = newAdmin();
        Member bidder = newMember();
        Auction auction = newAuction(newMember(), "입찰 있는 신고 경매");
        mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":305000}").cookie(cookieOf(bidder))).andExpect(status().isOk());
        long reportId = newReport(newMember(), "AUCTION", auction.getId());

        process(cookieOf(admin), reportId, body("AUCTION_STOPPED", "허위 정보가 확인됩니다", null)).andExpect(status().isOk());

        entityManager.clear();
        assertThat(pointBalanceRepository.findByMemberId(bidder.getId()).orElseThrow().getReservedPoint()).isEqualByComparingTo("0");
        assertThat(reload(reportId).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
    }

    @Test
    void 이미_중단된_경매는_조치_없이_신고만_처리_완료로_남기고_알린다() throws Exception {
        Member admin = newAdmin();
        Auction auction = newAuction(newMember(), "먼저 중단된 경매");
        long reportId = newReport(newMember(), "AUCTION", auction.getId());
        mockMvc.perform(post("/api/admin/auctions/" + auction.getId() + "/stop").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stoppedReason\":\"다른 경로로 먼저 중단\"}").cookie(cookieOf(admin))).andExpect(status().isOk());

        process(cookieOf(admin), reportId, body("AUCTION_STOPPED", "신고도 처리합니다", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.appliedAction.auctionStatus").value("STOPPED"))
                .andExpect(jsonPath("$.data.appliedAction.isAlreadyApplied").value(true));

        assertThat(reload(auction).getStoppedReason()).isEqualTo("다른 경로로 먼저 중단");   // 중단 사유를 덮어쓰지 않는다.
        assertThat(reload(reportId).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
    }

    @Test
    void 낙찰이나_유찰된_경매는_중단할_수_없어_409이고_신고는_접수_상태로_남는다() throws Exception {
        Member admin = newAdmin();
        for (AuctionStatus ended : List.of(AuctionStatus.SOLD, AuctionStatus.FAILED)) {
            Auction auction = newAuction(newMember(), "종료된 경매 " + ended);
            long reportId = newReport(newMember(), "AUCTION", auction.getId());
            setAuctionStatus(auction, ended);

            process(cookieOf(admin), reportId, body("AUCTION_STOPPED", "종료된 경매 중단 시도", null))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("AUCTION_STATUS_INVALID"));

            assertUntouched(reportId);
            assertThat(reload(auction).getAuctionStatus()).isEqualTo(ended);
            // 종료된 경매의 신고는 반려나 회원 제한으로 처리할 수 있다.
            process(cookieOf(admin), reportId, body("REJECTED", "종료된 경매라 반려합니다", null)).andExpect(status().isOk());
        }
    }

    @Test
    void 경매_중단은_경매_신고에만_쓸_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer, "거래 신고 경매");
        long memberReport = newReport(newMember(), "MEMBER", seller.getId());
        long tradeReport = newReport(buyer, "TRADE", tradeId);

        process(cookieOf(admin), memberReport, body("AUCTION_STOPPED", "경매 중단", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        process(cookieOf(admin), tradeReport, body("AUCTION_STOPPED", "경매 중단", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));

        assertUntouched(memberReport);
        assertUntouched(tradeReport);
    }

    // ---- 회원 이용 제한 ----

    @Test
    void 회원_신고를_이용_제한으로_처리하면_그_회원이_제한되고_이력이_남는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long reportId = newReport(newMember(), "MEMBER", target.getId());

        process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "불성실 거래가 반복됩니다", RESTRICT_TRADE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.actionType").value("MEMBER_RESTRICTED"))
                .andExpect(jsonPath("$.data.appliedAction.memberId").value(target.getId()))
                .andExpect(jsonPath("$.data.appliedAction.memberStatus").value("RESTRICTED_TRADE"))
                .andExpect(jsonPath("$.data.appliedAction.restrictionId").isNumber())
                .andExpect(jsonPath("$.data.appliedAction.isAlreadyApplied").value(false));

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
        assertThat(restrictionCount(target)).isEqualTo(1);
        Object[] row = (Object[]) entityManager.createQuery(
                "select r.reason, r.permanent, r.admin.id from MemberRestriction r where r.member.id = :m")
                .setParameter("m", target.getId()).getSingleResult();
        assertThat(row[0]).isEqualTo("불성실 거래가 반복됩니다");   // 처리 사유가 제한 사유가 된다.
        assertThat(row[1]).isEqualTo(true);
        assertThat(row[2]).isEqualTo(admin.getId());
        Report report = reload(reportId);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(report.getActiveKey()).isNull();
    }

    @Test
    void 경매_신고를_이용_제한으로_처리하면_판매자가_제한된다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Auction auction = newAuction(seller, "판매자 제한 경매");
        long reportId = newReport(newMember(), "AUCTION", auction.getId());

        process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "판매자의 반복 위반",
                "{\"restrictionLevel\":\"RESTRICTED_LOGIN\",\"isPermanent\":false,\"expiresAt\":\""
                        + java.time.OffsetDateTime.now().plusDays(3) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedAction.memberId").value(seller.getId()))
                .andExpect(jsonPath("$.data.appliedAction.memberStatus").value("RESTRICTED_LOGIN"));

        assertThat(reload(seller).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_LOGIN);
        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);   // 경매는 그대로다.
    }

    @Test
    void 거래_신고를_이용_제한으로_처리하면_신고자의_상대_당사자가_제한된다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Member buyer = newMember();
        long tradeId = newTrade(seller, buyer, "상대 제한 거래");
        long byBuyer = newReport(buyer, "TRADE", tradeId);
        long bySeller = newReport(seller, "TRADE", tradeId);

        process(cookieOf(admin), byBuyer, body("MEMBER_RESTRICTED", "판매자 미이행", RESTRICT_TRADE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.appliedAction.memberId").value(seller.getId()));
        assertThat(reload(seller).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
        assertThat(reload(buyer).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);

        // 판매자가 낸 신고는 구매자를 제한한다.
        process(cookieOf(admin), bySeller, body("MEMBER_RESTRICTED", "구매자 불성실", RESTRICT_TRADE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.appliedAction.memberId").value(buyer.getId()));
        assertThat(reload(buyer).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
    }

    @Test
    void 이미_제한된_회원은_조치_없이_신고만_처리_완료로_남기고_기존_제한을_바꾸지_않는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long first = newReport(newMember(), "MEMBER", target.getId());
        long second = newReport(newMember(), "MEMBER", target.getId());
        process(cookieOf(admin), first, body("MEMBER_RESTRICTED", "첫 제한", RESTRICT_TRADE)).andExpect(status().isOk());
        Long restrictionId = ((Number) entityManager.createQuery("select r.id from MemberRestriction r where r.member.id = :m")
                .setParameter("m", target.getId()).getSingleResult()).longValue();

        process(cookieOf(admin), second, body("MEMBER_RESTRICTED", "두 번째 신고도 처리",
                "{\"restrictionLevel\":\"RESTRICTED_LOGIN\",\"isPermanent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("PROCESSED"))
                .andExpect(jsonPath("$.data.appliedAction.memberStatus").value("RESTRICTED_TRADE"))
                .andExpect(jsonPath("$.data.appliedAction.restrictionId").value(restrictionId))
                .andExpect(jsonPath("$.data.appliedAction.isAlreadyApplied").value(true));

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);   // 수준을 바꾸지 않는다.
        assertThat(restrictionCount(target)).isEqualTo(1);
        assertThat(reload(second).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
    }

    @Test
    void 탈퇴한_회원이_대상이면_409이고_신고는_접수_상태로_남는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long reportId = newReport(newMember(), "MEMBER", target.getId());
        ReflectionTestUtils.setField(target, "memberStatus", MemberStatus.WITHDRAWN);
        memberRepository.save(target);

        process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "탈퇴 회원 제한 시도", RESTRICT_TRADE))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_WITHDRAWN"));

        assertUntouched(reportId);
        assertThat(restrictionCount(target)).isZero();
    }

    @Test
    void 이용_제한_입력이_잘못되면_400이고_이미_제한된_회원이어도_같은_검사를_한다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long reportId = newReport(newMember(), "MEMBER", target.getId());
        long other = newReport(newMember(), "MEMBER", target.getId());
        process(cookieOf(admin), other, body("MEMBER_RESTRICTED", "먼저 제한", RESTRICT_TRADE)).andExpect(status().isOk());

        String[] invalid = {
                "{\"restrictionLevel\":\"NORMAL\",\"isPermanent\":true}",                                // 제한 수준이 아님
                "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":false}",                     // 기간 지정인데 만료 시각 없음
                "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":true,\"expiresAt\":\"2099-01-01T00:00:00+09:00\"}",   // 영구인데 만료 시각
                "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":false,\"expiresAt\":\"2020-01-01T00:00:00+09:00\"}",  // 과거
                "{\"isPermanent\":true}",                                                                // 수준 없음
        };
        for (String restriction : invalid) {
            process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "입력 검사", restriction)).andExpect(status().isBadRequest());
        }
        assertUntouched(reportId);
    }

    // ---- 반려 ----

    @Test
    void 반려하면_조치_없이_신고만_반려_상태가_되고_같은_대상을_다시_신고할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member reporter = newMember();
        Auction auction = newAuction(newMember(), "반려 대상 경매");
        long reportId = newReport(reporter, "AUCTION", auction.getId());

        process(cookieOf(admin), reportId, body("REJECTED", "신고 사유가 확인되지 않습니다", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.actionType").value("REJECTED"))
                .andExpect(jsonPath("$.data.appliedAction").doesNotExist());

        Report report = reload(reportId);
        assertThat(report.getReportStatus()).isEqualTo(ReportStatus.REJECTED);
        assertThat(report.getActionReason()).isEqualTo("신고 사유가 확인되지 않습니다");
        assertThat(report.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(report.getActiveKey()).isNull();
        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.IN_PROGRESS);

        mockMvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"AUCTION\",\"targetId\":" + auction.getId() + ",\"reportReason\":\"FALSE_INFO\"}")
                .cookie(cookieOf(reporter))).andExpect(status().isOk());   // 반려 뒤에는 다시 신고할 수 있다.
    }

    @Test
    void 이미_처리하거나_반려한_신고는_다시_처리할_수_없고_결과가_바뀌지_않는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long rejected = newReport(newMember(), "MEMBER", target.getId());
        long processed = newReport(newMember(), "MEMBER", target.getId());
        process(cookieOf(admin), rejected, body("REJECTED", "반려합니다", null)).andExpect(status().isOk());
        process(cookieOf(admin), processed, body("MEMBER_RESTRICTED", "제한합니다", RESTRICT_TRADE)).andExpect(status().isOk());
        long restrictions = restrictionCount(target);

        for (long reportId : new long[] {rejected, processed}) {
            process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "다시 처리 시도", RESTRICT_TRADE))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_ALREADY_PROCESSED"));
            process(cookieOf(admin), reportId, body("REJECTED", "다시 반려 시도", null))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REPORT_ALREADY_PROCESSED"));
        }

        assertThat(reload(rejected).getActionType()).isEqualTo(ReportActionType.REJECTED);
        assertThat(reload(rejected).getActionReason()).isEqualTo("반려합니다");
        assertThat(reload(processed).getActionType()).isEqualTo(ReportActionType.MEMBER_RESTRICTED);
        assertThat(restrictionCount(target)).isEqualTo(restrictions);
    }

    // ---- 입력·권한 ----

    @Test
    void 처리_입력이_잘못되면_400이고_신고는_처리_전_그대로다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long reportId = newReport(newMember(), "MEMBER", target.getId());
        Cookie cookie = cookieOf(admin);

        process(cookie, reportId, "{\"actionReason\":\"결과 없음\"}").andExpect(status().isBadRequest());
        process(cookie, reportId, "{\"actionType\":\"REJECTED\"}").andExpect(status().isBadRequest());
        process(cookie, reportId, "{\"actionType\":\"SOMETHING\",\"actionReason\":\"없는 결과\"}").andExpect(status().isBadRequest());
        process(cookie, reportId, body("REJECTED", "   ", null)).andExpect(status().isBadRequest());
        process(cookie, reportId, body("REJECTED", "​ㅤ", null)).andExpect(status().isBadRequest());
        process(cookie, reportId, body("REJECTED", "가".repeat(501), null)).andExpect(status().isBadRequest());
        process(cookie, reportId, body("REJECTED", "제한 정보가 필요 없음", RESTRICT_TRADE)).andExpect(status().isBadRequest());
        process(cookie, reportId, body("MEMBER_RESTRICTED", "제한 정보 없음", null)).andExpect(status().isBadRequest());

        assertUntouched(reportId);
        assertThat(restrictionCount(target)).isZero();
    }

    @Test
    void 없는_신고는_404다() throws Exception {
        process(cookieOf(newAdmin()), 999_999_999L, body("REJECTED", "없는 신고", null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
    }

    @Test
    void 관리자만_신고를_조회하고_처리할_수_있다() throws Exception {
        Member member = newMember();
        long reportId = newReport(newMember(), "MEMBER", newMember().getId());

        process(null, reportId, body("REJECTED", "사유", null)).andExpect(status().isUnauthorized());
        process(cookieOf(member), reportId, body("REJECTED", "사유", null)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/reports").cookie(cookieOf(member))).andExpect(status().isForbidden());
        assertUntouched(reportId);
    }

    // ---- 동시 처리 ----

    @Test
    void 같은_신고를_두_관리자가_동시에_처리하면_한_번만_성립한다() throws Exception {
        Member target = newMember();
        for (int round = 0; round < 3; round++) {
            Member firstAdmin = newAdmin();
            Member secondAdmin = newAdmin();
            Member reportedMember = newMember();
            long reportId = newReport(newMember(), "MEMBER", reportedMember.getId());

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                go.await();
                return process(cookieOf(firstAdmin), reportId, body("MEMBER_RESTRICTED", "제한 처리 " + System.nanoTime(), RESTRICT_TRADE))
                        .andReturn().getResponse().getStatus();
            }));
            results.add(pool.submit(() -> {
                go.await();
                return process(cookieOf(secondAdmin), reportId, body("REJECTED", "반려 처리 " + System.nanoTime(), null))
                        .andReturn().getResponse().getStatus();
            }));
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            Report report = reload(reportId);
            assertThat(report.getReportStatus()).as("round %d", round).isNotEqualTo(ReportStatus.RECEIVED);
            // 제한이 걸렸다면 신고가 제한으로 처리된 경우뿐이고, 반려가 이겼다면 제한 이력이 없어야 한다.
            boolean restricted = report.getActionType() == ReportActionType.MEMBER_RESTRICTED;
            assertThat(restrictionCount(reportedMember)).as("round %d", round).isEqualTo(restricted ? 1 : 0);
            assertThat(reload(reportedMember).getMemberStatus()).as("round %d", round)
                    .isEqualTo(restricted ? MemberStatus.RESTRICTED_TRADE : MemberStatus.NORMAL);
        }
        assertThat(restrictionCount(target)).isZero();
    }

    @Test
    void 서로_다른_신고가_같은_회원을_동시에_제한해도_제한은_한_번만_걸린다() throws Exception {
        Member target = newMember();
        long first = newReport(newMember(), "MEMBER", target.getId());
        long second = newReport(newMember(), "MEMBER", target.getId());
        Member admin = newAdmin();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (long reportId : new long[] {first, second}) {
            results.add(pool.submit(() -> {
                go.await();
                return process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "동시 제한 " + reportId, RESTRICT_TRADE))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);   // 늦은 쪽은 이미 제한된 회원으로 신고만 처리한다.
        }
        pool.shutdown();

        assertThat(restrictionCount(target)).isEqualTo(1);
        assertThat(reload(first).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(reload(second).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
    }

    @Test
    void 같은_경매를_겨냥한_두_신고를_동시에_중단으로_처리해도_중단은_한_번만_성립하고_예약은_한_번만_풀린다() throws Exception {
        Member bidder = newMember();
        Auction auction = newAuction(newMember(), "동시 중단 경매");
        mockMvc.perform(post("/api/auctions/" + auction.getId() + "/bids").contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidPrice\":305000}").cookie(cookieOf(bidder))).andExpect(status().isOk());
        long first = newReport(newMember(), "AUCTION", auction.getId());
        long second = newReport(newMember(), "AUCTION", auction.getId());
        Member admin = newAdmin();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<String>> results = new ArrayList<>();
        for (long reportId : new long[] {first, second}) {
            results.add(pool.submit(() -> {
                go.await();
                var response = process(cookieOf(admin), reportId, body("AUCTION_STOPPED", "동시 중단 " + reportId, null))
                        .andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                return response.getContentAsString();
            }));
        }
        go.countDown();
        List<Boolean> alreadyApplied = new ArrayList<>();
        for (Future<String> result : results) {
            alreadyApplied.add(JsonPath.read(result.get(30, TimeUnit.SECONDS), "$.data.appliedAction.isAlreadyApplied"));
        }
        pool.shutdown();

        assertThat(alreadyApplied).containsExactlyInAnyOrder(false, true);   // 늦은 쪽은 이미 중단된 경매로 신고만 처리한다.
        assertThat(reload(auction).getAuctionStatus()).isEqualTo(AuctionStatus.STOPPED);
        assertThat(reload(first).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(reload(second).getReportStatus()).isEqualTo(ReportStatus.PROCESSED);
        assertThat(pointBalanceRepository.findByMemberId(bidder.getId()).orElseThrow().getReservedPoint()).isEqualByComparingTo("0");
    }

    @Test
    void 반려와_같은_대상의_재신고가_동시에_들어와도_접수_신고는_하나를_넘지_않는다() throws Exception {
        Member admin = newAdmin();
        for (int round = 0; round < 3; round++) {
            Auction auction = newAuction(newMember(), "재신고 경합 경매 " + round);
            long reportId = newReport(newMember(), "AUCTION", auction.getId());
            Member newReporter = newMember();

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Integer> rejecting = pool.submit(() -> {
                go.await();
                return process(cookieOf(admin), reportId, body("REJECTED", "경합 반려 " + System.nanoTime(), null))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> reporting = pool.submit(() -> {
                go.await();
                return mockMvc.perform(post("/api/reports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"AUCTION\",\"targetId\":" + auction.getId() + ",\"reportReason\":\"FALSE_INFO\"}")
                        .cookie(cookieOf(newReporter))).andReturn().getResponse().getStatus();
            });
            go.countDown();
            assertThat(rejecting.get(30, TimeUnit.SECONDS)).as("round %d", round).isEqualTo(200);
            assertThat(reporting.get(30, TimeUnit.SECONDS)).as("round %d", round).isIn(200, 409);   // 반려 전이면 중복 신고로 거절된다.
            pool.shutdown();

            entityManager.clear();
            long received = entityManager.createQuery(
                    "select count(r) from Report r where r.targetType = :t and r.targetId = :id and r.reportStatus = :s", Long.class)
                    .setParameter("t", negocut.report.entity.ReportTargetType.AUCTION).setParameter("id", auction.getId()).setParameter("s", ReportStatus.RECEIVED).getSingleResult();
            assertThat(received).as("round %d", round).isLessThanOrEqualTo(1);
            assertThat(reload(reportId).getReportStatus()).as("round %d", round).isEqualTo(ReportStatus.REJECTED);
        }
    }

    // ---- 대상이 사라진 경우 ----

    @Test
    void 경매_신고의_판매자나_거래_신고의_상대가_탈퇴했으면_제한은_409이고_반려는_할_수_있다() throws Exception {
        Member admin = newAdmin();
        Member seller = newMember();
        Auction auction = newAuction(seller, "판매자 탈퇴 경매");
        Member tradeSeller = newMember();
        Member tradeBuyer = newMember();
        long tradeId = newTrade(tradeSeller, tradeBuyer, "상대 탈퇴 거래");
        long auctionReport = newReport(newMember(), "AUCTION", auction.getId());
        long tradeReport = newReport(tradeBuyer, "TRADE", tradeId);
        for (Member gone : List.of(seller, tradeSeller)) {
            ReflectionTestUtils.setField(gone, "memberStatus", MemberStatus.WITHDRAWN);
            memberRepository.save(gone);
        }

        for (long reportId : new long[] {auctionReport, tradeReport}) {
            process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "탈퇴한 상대 제한 시도", RESTRICT_TRADE))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_WITHDRAWN"));
            assertUntouched(reportId);
            process(cookieOf(admin), reportId, body("REJECTED", "탈퇴 회원이라 반려", null)).andExpect(status().isOk());
        }
        assertThat(restrictionCount(seller)).isZero();
        assertThat(restrictionCount(tradeSeller)).isZero();
    }

    @Test
    void 관리자_계정을_겨냥한_회원_신고는_제한할_수_없어_404이고_신고는_접수_상태로_남는다() throws Exception {
        Member admin = newAdmin();
        Member targetAdmin = newAdmin();
        long reportId = newReport(newMember(), "MEMBER", targetAdmin.getId());

        process(cookieOf(admin), reportId, body("MEMBER_RESTRICTED", "관리자 제한 시도", RESTRICT_TRADE))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));

        assertUntouched(reportId);
        assertThat(reload(targetAdmin).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(restrictionCount(targetAdmin)).isZero();
    }

    // ---- 입력 경계 ----

    @Test
    void 만료_시각은_10년_이내만_허용하고_처리_사유는_500자까지_허용한다() throws Exception {
        Member admin = newAdmin();
        Member tooLong = newMember();
        long tooLongReport = newReport(newMember(), "MEMBER", tooLong.getId());
        String beyond = "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":false,\"expiresAt\":\""
                + java.time.OffsetDateTime.now().plusYears(10).plusDays(2) + "\"}";
        process(cookieOf(admin), tooLongReport, body("MEMBER_RESTRICTED", "10년 초과", beyond)).andExpect(status().isBadRequest());
        assertUntouched(tooLongReport);
        assertThat(restrictionCount(tooLong)).isZero();

        Member withinLimit = newMember();
        long withinReport = newReport(newMember(), "MEMBER", withinLimit.getId());
        String within = "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":false,\"expiresAt\":\""
                + java.time.OffsetDateTime.now().plusYears(9) + "\"}";
        process(cookieOf(admin), withinReport, body("MEMBER_RESTRICTED", "9년 제한", within)).andExpect(status().isOk());

        // 앞뒤 공백은 잘라 500자만 남으면 통과하고, 501자는 거절된다.
        long reasonReport = newReport(newMember(), "MEMBER", newMember().getId());
        process(cookieOf(admin), reasonReport, body("REJECTED", "가".repeat(501), null)).andExpect(status().isBadRequest());
        assertUntouched(reasonReport);
        process(cookieOf(admin), reasonReport, body("REJECTED", " " + "가".repeat(500) + " ", null)).andExpect(status().isOk());
        assertThat(reload(reasonReport).getActionReason()).hasSize(500);
    }

    // ---- 목록 ----

    @Test
    void 신고_목록은_상태로_거르고_최근_신고가_먼저이며_대상_표시와_처리_결과를_담는다() throws Exception {
        Member admin = newAdmin();
        Member reporter = newMember();
        Member targetMember = newMember();
        Auction auction = newAuction(newMember(), "목록 확인 경매");
        long memberReport = newReport(reporter, "MEMBER", targetMember.getId());
        long auctionReport = newReport(reporter, "AUCTION", auction.getId());
        process(cookieOf(admin), memberReport, body("REJECTED", "목록용 반려", null)).andExpect(status().isOk());

        String received = mockMvc.perform(get("/api/admin/reports?status=RECEIVED&size=100").cookie(cookieOf(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> receivedIds = JsonPath.read(received, "$.data.content[*].reportId");
        assertThat(receivedIds).contains((int) auctionReport).doesNotContain((int) memberReport);
        String receivedItem = JsonPath.<List<java.util.Map<String, Object>>>read(received, "$.data.content[?(@.reportId == " + auctionReport + ")]")
                .get(0).toString();
        assertThat(receivedItem).contains("targetLabel=목록 확인 경매", "reporterNickname=" + reporter.getNickname(), "reportStatus=RECEIVED");

        mockMvc.perform(get("/api/admin/reports?status=REJECTED&size=100").cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.reportId == " + memberReport + ")].actionType").value("REJECTED"))
                .andExpect(jsonPath("$.data.content[?(@.reportId == " + memberReport + ")].actionReason").value("목록용 반려"));

        String all = mockMvc.perform(get("/api/admin/reports?size=100").cookie(cookieOf(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> allIds = JsonPath.read(all, "$.data.content[*].reportId");
        assertThat(allIds.indexOf((int) auctionReport)).isLessThan(allIds.indexOf((int) memberReport));   // 최근 신고가 먼저다.
    }

    @Test
    void 신고_목록의_페이지_범위가_잘못되면_400이다() throws Exception {
        Cookie cookie = cookieOf(newAdmin());
        mockMvc.perform(get("/api/admin/reports?page=-1").cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/reports?size=0").cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/reports?size=101").cookie(cookie)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/reports?status=UNKNOWN").cookie(cookie)).andExpect(status().isBadRequest());
    }
}
