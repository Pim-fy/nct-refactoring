package negocut.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRestriction;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.member.repository.MemberRestrictionRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 관리자 회원 관리: 회원 목록과 이용 제한·해제 (기능 명세서 3-10 회원 이용 제한, 2-2 회원 상태)
@SpringBootTest
@AutoConfigureMockMvc
class AdminMemberTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MemberRestrictionRepository restrictionRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;
    @PersistenceContext private EntityManager entityManager;

    // ---- 준비 도우미 ----

    private Member createMember(MemberRole role, MemberStatus status) {
        String suffix = TestSuffix.next();
        Member member = Member.create("am" + suffix, passwordEncoder.encode("abcd1234"),
                "관리" + suffix.substring(2), "am" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberRole", role);
        ReflectionTestUtils.setField(member, "memberStatus", status);
        member = memberRepository.save(member);
        if (role == MemberRole.MEMBER) {
            pointBalanceRepository.save(PointBalance.createEmpty(member));
        }
        return member;
    }

    // 아이디를 정해 만든다. 이메일은 아이디에서 만들고 닉네임은 겹치지 않게 번호를 붙인다.
    private Member memberWithLoginId(String loginId) {
        Member member = Member.create(loginId, passwordEncoder.encode("abcd1234"), "특" + TestSuffix.next(), loginId + "@example.com", "010-1111-2222");
        return memberRepository.save(member);
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

    private String inDays(long days) {
        return OffsetDateTime.now(ZONE).plusDays(days).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String restrictBody(String level, boolean permanent, String expiresAt, String reason, Long expectedId) {
        return "{\"restrictionLevel\":" + (level == null ? "null" : "\"" + level + "\"")
                + ",\"isPermanent\":" + permanent
                + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt + "\"")
                + ",\"reason\":\"" + reason + "\""
                + (expectedId == null ? "" : ",\"expectedRestrictionId\":" + expectedId) + "}";
    }

    private ResultActions restrict(Cookie cookie, long memberId, String body) throws Exception {
        var request = post("/api/admin/members/" + memberId + "/restrictions").contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    // 화면이 확인한 현재 제한의 식별자(restrictionId)를 함께 보낸다. 생략하면 지금의 현재 제한을 확인한 것으로 한다.
    private ResultActions release(Cookie cookie, long memberId, String reason) throws Exception {
        return release(cookie, memberId, currentRestrictionId(memberId), reason);
    }

    private ResultActions release(Cookie cookie, long memberId, Long restrictionId, String reason) throws Exception {
        var request = delete("/api/admin/members/" + memberId + "/restrictions").contentType(MediaType.APPLICATION_JSON)
                .content("{" + (restrictionId == null ? "" : "\"restrictionId\":" + restrictionId + ",") + "\"reason\":\"" + reason + "\"}");
        if (cookie != null) {
            request.cookie(cookie);
        }
        return mockMvc.perform(request);
    }

    private ResultActions restrictOk(Member admin, Member target, String level, boolean permanent, String reason, Long expectedId) throws Exception {
        return restrict(cookieOf(admin), target.getId(), restrictBody(level, permanent, permanent ? null : inDays(7), reason, expectedId))
                .andExpect(status().isOk());
    }

    private Member reload(Member member) {
        return memberRepository.findById(member.getId()).orElseThrow();
    }

    private List<MemberRestriction> historyOf(Member member) {
        return entityManager.createQuery("select r from MemberRestriction r where r.member.id = :id order by r.id", MemberRestriction.class)
                .setParameter("id", member.getId()).getResultList();
    }

    // 회원의 현재 제한(가장 최근 열린 행)의 식별자. 제한 중이 아니면 null이다.
    private Long currentRestrictionId(long memberId) {
        return restrictionRepository.findByMemberIdAndReleasedAtIsNullOrderByIdDesc(memberId).stream().findFirst()
                .map(MemberRestriction::getId).orElse(null);
    }

    private long openCount(Member member) {
        return historyOf(member).stream().filter(r -> r.getReleasedAt() == null).count();
    }

    // ---- 제한 ----

    @Test
    void 기간을_지정해_거래_제한을_걸면_상태와_이력이_함께_갱신된다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        String expiresAt = inDays(7);

        String response = restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_TRADE", false, expiresAt, "거래 미이행이 반복됩니다", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(target.getId()))
                .andExpect(jsonPath("$.data.memberStatus").value("RESTRICTED_TRADE"))
                .andExpect(jsonPath("$.data.restrictionId").isNumber())
                .andExpect(jsonPath("$.data.startedAt").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
        List<MemberRestriction> history = historyOf(target);
        assertThat(history).hasSize(1);
        MemberRestriction saved = history.get(0);
        assertThat(saved.getId()).isEqualTo(((Number) JsonPath.read(response, "$.data.restrictionId")).longValue());
        assertThat(saved.getRestrictionLevel()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
        assertThat(saved.getReason()).isEqualTo("거래 미이행이 반복됩니다");
        assertThat(saved.isPermanent()).isFalse();
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now(ZONE).plusDays(6));
        assertThat(saved.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(saved.getReleasedAt()).isNull();
        assertThat(saved.getReleasedByAdmin()).isNull();
    }

    @Test
    void 영구_제한은_만료_시각이_없다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();

        restrictOk(admin, target, "RESTRICTED_LOGIN", true, "반복적인 사기 행위", null)
                .andExpect(jsonPath("$.data.memberStatus").value("RESTRICTED_LOGIN"))
                .andExpect(jsonPath("$.data.expiresAt").doesNotExist());

        MemberRestriction saved = historyOf(target).get(0);
        assertThat(saved.isPermanent()).isTrue();
        assertThat(saved.getExpiresAt()).isNull();
    }

    @Test
    void 거래_제한은_조회만_되고_로그인_정지는_다음_요청부터_막힌다() throws Exception {
        Member admin = newAdmin();
        Member tradeLimited = newMember();
        Member loginStopped = newMember();
        mockMvc.perform(get("/api/members/me").cookie(cookieOf(loginStopped))).andExpect(status().isOk());

        restrictOk(admin, tradeLimited, "RESTRICTED_TRADE", true, "거래 제한 사유", null);
        restrictOk(admin, loginStopped, "RESTRICTED_LOGIN", true, "로그인 정지 사유", null);

        mockMvc.perform(get("/api/members/me").cookie(cookieOf(tradeLimited))).andExpect(status().isOk());
        mockMvc.perform(post("/api/members/me/points/charge").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}")
                .cookie(cookieOf(tradeLimited))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));
        // 같은 토큰이어도 상태가 바뀌면 다음 요청부터 반영된다. (요청마다 DB의 현재 상태로 판단)
        mockMvc.perform(get("/api/members/me").cookie(cookieOf(loginStopped))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }

    @Test
    void 이미_제한된_회원은_교체_확인_없이는_바꾸지_않는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "처음 사유", null);

        restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_LOGIN", true, null, "새 사유", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_RESTRICTED"));
        restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_TRADE", true, null, "같은 수준 새 사유", null))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_RESTRICTED"));

        // 거부되면 상태와 이력은 그대로다.
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);
        assertThat(historyOf(target)).hasSize(1);
        assertThat(historyOf(target).get(0).getReason()).isEqualTo("처음 사유");
    }

    @Test
    void 교체를_확인하면_기존_이력을_닫고_새_이력을_추가한다() throws Exception {
        Member admin = newAdmin();
        Member other = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "처음 사유", null);

        // 수준을 바꾸는 교체
        restrictOk(other, target, "RESTRICTED_LOGIN", true, "더 무거운 사유", currentRestrictionId(target.getId()))
                .andExpect(jsonPath("$.data.memberStatus").value("RESTRICTED_LOGIN"));
        // 같은 수준으로 기간·사유만 바꾸는 교체
        restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_LOGIN", false, inDays(3), "기간을 정해 다시 제한", currentRestrictionId(target.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.memberStatus").value("RESTRICTED_LOGIN"));

        List<MemberRestriction> history = historyOf(target);
        assertThat(history).hasSize(3);
        assertThat(openCount(target)).isEqualTo(1);   // 한 회원의 현재 제한은 해제되지 않은 행 하나다.
        MemberRestriction first = history.get(0);
        assertThat(first.getReleasedAt()).isNotNull();
        assertThat(first.getReleasedByAdmin().getId()).isEqualTo(other.getId());
        assertThat(first.getReleaseReason()).isEqualTo("이용 제한 교체");
        assertThat(history.get(1).getReleasedByAdmin().getId()).isEqualTo(admin.getId());
        MemberRestriction current = history.get(2);
        assertThat(current.getReleasedAt()).isNull();
        assertThat(current.getReason()).isEqualTo("기간을 정해 다시 제한");
        assertThat(current.isPermanent()).isFalse();
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_LOGIN);
    }

    @Test
    void 확인한_제한이_그사이_바뀌었으면_교체와_해제는_409다() throws Exception {
        Member admin = newAdmin();
        Member other = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "처음 사유", null);
        long seenId = currentRestrictionId(target.getId());

        // 관리자가 제한을 보고 모달을 연 사이에 다른 관리자가 제한을 바꿨다.
        restrictOk(other, target, "RESTRICTED_LOGIN", true, "다른 관리자가 높인 사유", seenId);
        long newId = currentRestrictionId(target.getId());
        assertThat(newId).isNotEqualTo(seenId);

        // 옛 제한을 확인한 요청은 덮어쓰지도 해제하지도 못한다.
        restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_TRADE", true, null, "옛 화면의 교체", seenId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_RESTRICTION_CHANGED"));
        release(cookieOf(admin), target.getId(), seenId, "옛 화면의 해제")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_RESTRICTION_CHANGED"));
        // 확인한 제한이 없는 해제 요청도 제한 중인 회원에게는 통하지 않는다.
        release(cookieOf(admin), target.getId(), null, "확인 없는 해제")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_RESTRICTION_CHANGED"));
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_LOGIN);
        assertThat(currentRestrictionId(target.getId())).isEqualTo(newId);
        assertThat(historyOf(target)).hasSize(2);

        // 현재 제한을 확인하면 된다. 해제된 뒤에는 옛 식별자로 교체할 수도 없다.
        release(cookieOf(admin), target.getId(), newId, "현재 제한 해제").andExpect(status().isOk());
        restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_TRADE", true, null, "해제된 제한을 확인한 교체", newId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("MEMBER_RESTRICTION_CHANGED"));
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(historyOf(target)).hasSize(2);
    }

    @Test
    void 열린_제한_행이_둘이어도_목록을_읽고_제한과_해제가_모두_닫는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "정상 제한", null);
        // 데이터가 어긋나 열린 행이 하나 더 생겼다. (DB 직접 수정 같은 경우)
        LocalDateTime now = LocalDateTime.now(ZONE);
        MemberRestriction extra = restrictionRepository.save(MemberRestriction.start(reload(target), MemberStatus.RESTRICTED_TRADE,
                "어긋난 행", true, now, null, admin));
        assertThat(openCount(target)).isEqualTo(2);

        // 목록은 500이 아니라 가장 최근 행을 현재 제한으로 보여 준다.
        mockMvc.perform(get("/api/admin/members").param("keyword", target.getLoginId()).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].restriction.restrictionId").value(extra.getId()));

        restrictOk(admin, target, "RESTRICTED_LOGIN", true, "둘을 닫고 교체", extra.getId());
        assertThat(openCount(target)).isEqualTo(1);
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_LOGIN);

        restrictionRepository.save(MemberRestriction.start(reload(target), MemberStatus.RESTRICTED_LOGIN, "또 어긋난 행", true, now, null, admin));
        assertThat(openCount(target)).isEqualTo(2);
        release(cookieOf(admin), target.getId(), "둘 다 해제").andExpect(status().isOk());
        assertThat(openCount(target)).isZero();
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
    }

    @Test
    void 만료_시각은_오프셋과_상관없이_같은_시각으로_저장되고_상한을_넘으면_400이다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        Cookie cookie = cookieOf(admin);
        LocalDateTime base = LocalDateTime.now(ZONE).plusDays(3).withNano(0);
        OffsetDateTime baseOffset = base.atZone(ZONE).toOffsetDateTime();
        String utc = baseOffset.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String west = baseOffset.withOffsetSameInstant(ZoneOffset.ofHours(-5)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        restrict(cookie, target.getId(), restrictBody("RESTRICTED_TRADE", false, utc, "UTC 오프셋", null)).andExpect(status().isOk());
        assertThat(historyOf(target).get(0).getExpiresAt()).isEqualTo(base);
        restrict(cookie, target.getId(), restrictBody("RESTRICTED_TRADE", false, west, "-05:00 오프셋", currentRestrictionId(target.getId())))
                .andExpect(status().isOk());
        assertThat(historyOf(target).get(1).getExpiresAt()).isEqualTo(base);

        // 상한(10년)을 넘거나 DB가 담지 못하는 값은 500이 아니라 400이다.
        long id = target.getId();
        long expected = currentRestrictionId(id);
        String tooFar = OffsetDateTime.now(ZONE).plusYears(11).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, tooFar, "너무 먼 만료", expected)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, "9999-12-31T23:59:59-12:00", "범위 밖 만료", expected))
                .andExpect(status().isBadRequest());
        String edge = OffsetDateTime.now(ZONE).plusYears(10).minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, edge, "상한 안쪽 만료", expected)).andExpect(status().isOk());
    }

    @Test
    void 같은_제한_요청을_바로_다시_보내도_중복_요청으로_재생되지_않고_서버가_새로_판정한다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        String body = restrictBody("RESTRICTED_TRADE", true, null, "같은 본문 반복", null);

        restrict(cookieOf(admin), target.getId(), body).andExpect(status().isOk());
        release(cookieOf(admin), target.getId(), "같은 본문 해제").andExpect(status().isOk());
        // 같은 본문이 5초 안에 다시 와도 이전 응답을 재생하지 않고 다시 제한한다.
        restrict(cookieOf(admin), target.getId(), body).andExpect(status().isOk());
        restrict(cookieOf(admin), target.getId(), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_RESTRICTED"));
        release(cookieOf(admin), target.getId(), "같은 본문 해제").andExpect(status().isOk());

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(historyOf(target)).hasSize(2);
    }

    // ---- 해제 ----

    @Test
    void 해제하면_정상으로_돌아가고_해제_이력이_남는다() throws Exception {
        Member admin = newAdmin();
        Member releaser = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_LOGIN", true, "로그인 정지", null);
        mockMvc.perform(get("/api/members/me").cookie(cookieOf(target))).andExpect(status().isForbidden());

        release(cookieOf(releaser), target.getId(), "이의 신청이 확인됩니다").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(target.getId()))
                .andExpect(jsonPath("$.data.memberStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.releasedAt").isString());

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        MemberRestriction saved = historyOf(target).get(0);
        assertThat(saved.getReleasedAt()).isNotNull();
        assertThat(saved.getReleasedByAdmin().getId()).isEqualTo(releaser.getId());
        assertThat(saved.getReleaseReason()).isEqualTo("이의 신청이 확인됩니다");
        assertThat(saved.getAdmin().getId()).isEqualTo(admin.getId());
        mockMvc.perform(get("/api/members/me").cookie(cookieOf(target))).andExpect(status().isOk());
        // 해제한 뒤에는 새로 제한할 수 있다.
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "다시 제한", null);
        assertThat(historyOf(target)).hasSize(2);
        assertThat(openCount(target)).isEqualTo(1);
    }

    @Test
    void 제한_중이_아닌_회원의_해제는_409다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();

        release(cookieOf(admin), target.getId(), "해제 사유").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_RESTRICTED"));
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "제한", null);
        release(cookieOf(admin), target.getId(), "해제 사유").andExpect(status().isOk());
        release(cookieOf(admin), target.getId(), "다시 해제").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_NOT_RESTRICTED"));
    }

    // ---- 대상 ----

    @Test
    void 없는_회원과_관리자_계정은_404이고_탈퇴_회원은_409다() throws Exception {
        Member admin = newAdmin();
        Member otherAdmin = newAdmin();
        Member withdrawn = createMember(MemberRole.MEMBER, MemberStatus.WITHDRAWN);
        String body = restrictBody("RESTRICTED_TRADE", true, null, "제한 사유", null);

        restrict(cookieOf(admin), 999999999L, body).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        release(cookieOf(admin), 999999999L, "사유").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        // 다른 관리자와 본인 모두 대상이 아니다.
        restrict(cookieOf(admin), otherAdmin.getId(), body).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        restrict(cookieOf(admin), admin.getId(), body).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("MEMBER_NOT_FOUND"));
        release(cookieOf(admin), admin.getId(), "사유").andExpect(status().isNotFound());
        restrict(cookieOf(admin), withdrawn.getId(), body).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_WITHDRAWN"));
        release(cookieOf(admin), withdrawn.getId(), "사유").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MEMBER_ALREADY_WITHDRAWN"));

        assertThat(reload(admin).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(reload(otherAdmin).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(reload(withdrawn).getMemberStatus()).isEqualTo(MemberStatus.WITHDRAWN);
        assertThat(historyOf(withdrawn)).isEmpty();
    }

    // ---- 입력 검증 ----

    @Test
    void 잘못된_제한_입력은_400이고_아무것도_바뀌지_않는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        long id = target.getId();
        Cookie cookie = cookieOf(admin);
        String past = OffsetDateTime.now(ZONE).minusMinutes(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // 제한 수준이 아니거나 비었음
        restrict(cookie, id, restrictBody("NORMAL", true, null, "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("WITHDRAWN", true, null, "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody(null, true, null, "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("NOT_A_LEVEL", true, null, "사유", null)).andExpect(status().isBadRequest());
        // 기간 지정인데 만료 시각이 없거나 과거이거나 오프셋이 없음, 영구인데 만료 시각이 있음
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, null, "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, past, "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", false, "2099-01-01T00:00:00", "사유", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, inDays(1), "사유", null)).andExpect(status().isBadRequest());
        // 사유가 비었거나 공백(제로폭·한글 채움 문자 포함)이거나 500자 초과
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "   ", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "\\u200b\\u3164", null)).andExpect(status().isBadRequest());
        // 한글 채움 문자류·결합 문자·변형 선택자·제어 문자만 있는 사유는 읽을 수 있는 글자가 없어 400이다.
        for (String invisible : new String[] {"\\u115f\\u1160", "\\uffa0", "\\u034f", "\\ufe0f", "\\u2800", "\\u0000", "\\u0007"}) {
            restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, invisible, null)).andExpect(status().isBadRequest());
        }
        // 읽을 수 있는 글자가 있어도 제어 문자(NUL)가 섞이면 400이다.
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "사유\\u0000", null)).andExpect(status().isBadRequest());
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "가".repeat(501), null)).andExpect(status().isBadRequest());
        // 필수 값 누락
        restrict(cookie, id, "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"reason\":\"사유\"}").andExpect(status().isBadRequest());
        restrict(cookie, id, "{\"restrictionLevel\":\"RESTRICTED_TRADE\",\"isPermanent\":true}").andExpect(status().isBadRequest());
        restrict(cookie, id, "{}").andExpect(status().isBadRequest());
        // 경계: 500자는 허용
        restrict(cookie, id, restrictBody("RESTRICTED_TRADE", true, null, "가".repeat(500), null)).andExpect(status().isOk());

        assertThat(historyOf(target)).hasSize(1);
    }

    @Test
    void 해제_사유가_비었거나_길면_400이다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "제한", null);

        release(cookieOf(admin), target.getId(), "").andExpect(status().isBadRequest());
        release(cookieOf(admin), target.getId(), "  \\u200b ").andExpect(status().isBadRequest());
        release(cookieOf(admin), target.getId(), "\\u115f\\u0000").andExpect(status().isBadRequest());
        release(cookieOf(admin), target.getId(), "가".repeat(501)).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/admin/members/" + target.getId() + "/restrictions").cookie(cookieOf(admin)))
                .andExpect(status().isBadRequest());
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.RESTRICTED_TRADE);

        release(cookieOf(admin), target.getId(), "가".repeat(500)).andExpect(status().isOk());
    }

    // ---- 권한 ----

    @Test
    void 관리자만_쓸_수_있다() throws Exception {
        Member member = newMember();
        Member target = newMember();
        String body = restrictBody("RESTRICTED_TRADE", true, null, "사유", null);

        restrict(null, target.getId(), body).andExpect(status().isUnauthorized());
        release(null, target.getId(), "사유").andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/members")).andExpect(status().isUnauthorized());
        restrict(cookieOf(member), target.getId(), body).andExpect(status().isForbidden());
        release(cookieOf(member), target.getId(), "사유").andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/members").cookie(cookieOf(member))).andExpect(status().isForbidden());

        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
        assertThat(historyOf(target)).isEmpty();
    }

    // ---- 목록 ----

    @Test
    void 회원_목록은_최신순이고_현재_제한을_담는다() throws Exception {
        Member admin = newAdmin();
        Member restricted = newMember();
        Member normal = newMember();
        restrictOk(admin, restricted, "RESTRICTED_TRADE", false, "목록 확인 사유", null);
        String keyword = restricted.getLoginId();

        String response = mockMvc.perform(get("/api/admin/members").param("keyword", keyword).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].memberId").value(restricted.getId()))
                .andExpect(jsonPath("$.data.content[0].loginId").value(restricted.getLoginId()))
                .andExpect(jsonPath("$.data.content[0].email").value(restricted.getEmail()))
                .andExpect(jsonPath("$.data.content[0].memberStatus").value("RESTRICTED_TRADE"))
                .andExpect(jsonPath("$.data.content[0].memberRole").value("MEMBER"))
                .andExpect(jsonPath("$.data.content[0].restriction.restrictionLevel").value("RESTRICTED_TRADE"))
                .andExpect(jsonPath("$.data.content[0].restriction.reason").value("목록 확인 사유"))
                .andExpect(jsonPath("$.data.content[0].restriction.isPermanent").value(false))
                .andExpect(jsonPath("$.data.content[0].restriction.expiresAt").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(response, "$.data.content[0].restriction.restrictionId").longValue())
                .isEqualTo(historyOf(restricted).get(0).getId());

        // 제한이 없는 회원은 restriction이 null이다. 해제한 회원도 같다.
        mockMvc.perform(get("/api/admin/members").param("keyword", normal.getLoginId()).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.content[0].restriction").value((Object) null));
        release(cookieOf(admin), restricted.getId(), "해제").andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/members").param("keyword", keyword).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.content[0].memberStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.content[0].restriction").value((Object) null));

        // 최신 가입 회원이 먼저다.
        mockMvc.perform(get("/api/admin/members").param("size", "2").cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.content[0].memberId").value(normal.getId()))
                .andExpect(jsonPath("$.data.content[1].memberId").value(restricted.getId()));
    }

    @Test
    void 상태와_검색어로_거르고_검색어의_특수_문자는_글자_그대로_찾는다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_LOGIN", true, "필터 확인", null);

        mockMvc.perform(get("/api/admin/members").param("status", "RESTRICTED_LOGIN").param("keyword", target.getEmail()).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/admin/members").param("status", "NORMAL").param("keyword", target.getEmail()).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get("/api/admin/members").param("keyword", target.getNickname()).cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mockMvc.perform(get("/api/admin/members").param("keyword", "  " + target.getLoginId() + "  ").cookie(cookieOf(admin)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        // 없는 상태 값은 400이다.
        mockMvc.perform(get("/api/admin/members").param("status", "NOPE").cookie(cookieOf(admin))).andExpect(status().isBadRequest());
    }

    @Test
    void 검색어의_와일드카드_문자는_글자_그대로_찾는다() throws Exception {
        Member admin = newAdmin();
        String suffix = TestSuffix.next();
        // 각 쌍은 특수 문자 한 글자만 다르다. 특수 문자가 와일드카드로 읽히면 두 계정이 모두 걸린다.
        Member underscore = memberWithLoginId("ek_" + suffix);
        memberWithLoginId("ekx" + suffix);
        Member percent = memberWithLoginId("pc%" + suffix);
        memberWithLoginId("pcy" + suffix);
        Member bang = memberWithLoginId("bg!" + suffix);
        memberWithLoginId("bgz" + suffix);

        assertOnlyMatch(admin, "ek_" + suffix, underscore);
        assertOnlyMatch(admin, "pc%" + suffix, percent);
        assertOnlyMatch(admin, "bg!" + suffix, bang);
        assertOnlyMatch(admin, "_" + suffix, underscore);   // 앞쪽 와일드카드 자리에도 글자로 읽는다.
        assertOnlyMatch(admin, "%" + suffix, percent);
        assertOnlyMatch(admin, "!" + suffix, bang);
        // 100자까지는 검색하고, 넘으면 400이다.
        mockMvc.perform(get("/api/admin/members").param("keyword", "a".repeat(100)).cookie(cookieOf(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/members").param("keyword", "a".repeat(101)).cookie(cookieOf(admin))).andExpect(status().isBadRequest());
    }

    private void assertOnlyMatch(Member admin, String keyword, Member expected) throws Exception {
        mockMvc.perform(get("/api/admin/members").param("keyword", keyword).cookie(cookieOf(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].memberId").value(expected.getId()));
    }

    @Test
    void 목록의_page와_size가_잘못되면_400이다() throws Exception {
        Member admin = newAdmin();
        for (String[] bad : new String[][] {{"page", "-1"}, {"size", "0"}, {"size", "101"}, {"page", "abc"}}) {
            mockMvc.perform(get("/api/admin/members").param(bad[0], bad[1]).cookie(cookieOf(admin))).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/admin/members").param("size", "100").cookie(cookieOf(admin))).andExpect(status().isOk());
    }

    // ---- 동시성 ----

    @Test
    void 같은_회원에게_동시에_제한을_걸어도_한_건만_성립한다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        String[] reasons = {"동시 제한 1", "동시 제한 2", "동시 제한 3", "동시 제한 4", "동시 제한 5", "동시 제한 6"};

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(reasons.length);
        List<Future<Integer>> results = new ArrayList<>();
        for (String reason : reasons) {
            results.add(pool.submit(() -> {
                go.await();
                // 본문이 모두 달라야 중복 요청 방지가 아니라 회원 행 잠금과 교체 확인이 막는 경로를 탄다.
                return restrict(cookieOf(admin), target.getId(), restrictBody("RESTRICTED_TRADE", true, null, reason, null))
                        .andReturn().getResponse().getStatus();
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
        assertThat(historyOf(target)).hasSize(1);
        assertThat(openCount(target)).isEqualTo(1);
    }

    @Test
    void 동시에_교체를_확인해도_현재_제한은_하나다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "처음 제한", null);
        long seenId = currentRestrictionId(target.getId());
        String[] levels = {"RESTRICTED_LOGIN", "RESTRICTED_TRADE", "RESTRICTED_LOGIN", "RESTRICTED_TRADE"};

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(levels.length);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            String reason = "동시 교체 " + i;
            results.add(pool.submit(() -> {
                go.await();
                return restrict(cookieOf(admin), target.getId(), restrictBody(level, true, null, reason, seenId)).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        // 모두 같은 제한을 확인하고 보냈으므로 먼저 처리된 한 건만 교체하고, 나머지는 제한이 바뀐 것을 보고 거부된다.
        assertThat(statuses.stream().filter(s -> s == 200).count()).as("%s", statuses).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as("%s", statuses).isEqualTo(levels.length - 1);
        assertThat(historyOf(target)).hasSize(2);
        assertThat(openCount(target)).isEqualTo(1);
        MemberRestriction current = historyOf(target).stream().filter(r -> r.getReleasedAt() == null).findFirst().orElseThrow();
        assertThat(reload(target).getMemberStatus()).isEqualTo(current.getRestrictionLevel());   // 현재 상태와 열린 이력이 어긋나지 않는다.
    }

    @Test
    void 동시에_해제해도_한_번만_성립한다() throws Exception {
        Member admin = newAdmin();
        Member target = newMember();
        restrictOk(admin, target, "RESTRICTED_TRADE", true, "해제 경쟁", null);
        String[] reasons = {"동시 해제 1", "동시 해제 2", "동시 해제 3", "동시 해제 4"};

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(reasons.length);
        List<Future<Integer>> results = new ArrayList<>();
        for (String reason : reasons) {
            results.add(pool.submit(() -> {
                go.await();
                return release(cookieOf(admin), target.getId(), reason).andReturn().getResponse().getStatus();
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
        assertThat(openCount(target)).isZero();
        assertThat(reload(target).getMemberStatus()).isEqualTo(MemberStatus.NORMAL);
    }
}
