package negocut.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.support.TestSuffix;

// 인증 필터가 요청마다 회원의 현재 상태·역할을 DB에서 읽어 로그인 정지·탈퇴를 막고,
// 인터셉터가 거래 제한 회원의 쓰기 요청을 기본으로 막는지 확인한다. (기능 명세서 2-2)
@SpringBootTest
@AutoConfigureMockMvc
class MemberStatusGuardTest {

    private static final String PASSWORD = "abcd1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private PointBalanceRepository pointBalanceRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider tokenProvider;

    private Member createMember(MemberStatus status, MemberRole role) {
        String suffix = TestSuffix.next();
        Member member = Member.create("st" + suffix, passwordEncoder.encode(PASSWORD),
                "nick" + suffix.substring(2), "st" + suffix + "@example.com", "010-1111-2222");
        ReflectionTestUtils.setField(member, "memberStatus", status);
        ReflectionTestUtils.setField(member, "memberRole", role);
        member = memberRepository.save(member);
        pointBalanceRepository.save(PointBalance.createEmpty(member));
        return member;
    }

    // 로그인 API는 정지·탈퇴 회원을 거부하므로, 토큰을 직접 만들어 "이미 받아 둔 유효한 토큰"을 흉내 낸다.
    private Cookie accessCookie(Member member) {
        return new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), member.getMemberRole()));
    }

    private void assertAuthCookiesCleared(MvcResult result) {
        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).anyMatch(c -> c.startsWith("ACCESS_TOKEN=") && c.contains("Max-Age=0"));
        assertThat(setCookies).anyMatch(c -> c.startsWith("REFRESH_TOKEN=") && c.contains("Max-Age=0"));
    }

    // ---- 로그인 정지·탈퇴·회원 없음 ----

    @Test
    void 정상_회원은_인증이_필요한_조회를_할_수_있다() throws Exception {
        Member member = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);

        mockMvc.perform(get("/api/members/me").cookie(accessCookie(member)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(member.getId()));
    }

    @Test
    void 로그인_정지_회원은_유효한_토큰이어도_403이고_쿠키가_지워진다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.MEMBER);

        MvcResult result = mockMvc.perform(get("/api/members/me").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"))
                .andReturn();

        assertAuthCookiesCleared(result);
    }

    @Test
    void 탈퇴_회원은_유효한_토큰이어도_403이고_쿠키가_지워진다() throws Exception {
        Member member = createMember(MemberStatus.WITHDRAWN, MemberRole.MEMBER);

        MvcResult result = mockMvc.perform(get("/api/members/me").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"))
                .andReturn();

        assertAuthCookiesCleared(result);
    }

    @Test
    void 로그인_정지_회원은_쓰기_요청도_403이다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.MEMBER);

        mockMvc.perform(post("/api/auth/logout").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }

    @Test
    void 토큰은_유효한데_회원이_없으면_401이고_쿠키는_지우지_않는다() throws Exception {
        Cookie cookie = new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(999_999_999L, MemberRole.MEMBER));

        MvcResult result = mockMvc.perform(get("/api/members/me").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andReturn();

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void 로그인_정지_회원의_쿠키가_남아_있어도_공개_경로는_영향이_없다() throws Exception {
        Member stopped = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.MEMBER);
        Member other = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);

        mockMvc.perform(get("/api/agreements").cookie(accessCookie(stopped)))
                .andExpect(status().isOk());

        // 같은 브라우저에서 다른 계정으로 로그인하는 흐름
        mockMvc.perform(post("/api/auth/login").cookie(accessCookie(stopped)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + other.getLoginId() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
    }

    // ---- 역할은 토큰이 아니라 DB 값 ----

    @Test
    void 토큰은_관리자여도_DB_역할이_회원이면_관리자_경로가_거부된다() throws Exception {
        Member member = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);
        Cookie staleAdminToken = new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(member.getId(), MemberRole.ADMIN));

        mockMvc.perform(get("/api/admin/anything").cookie(staleAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void 토큰은_회원이어도_DB_역할이_관리자면_관리자_경로의_권한_검사를_통과한다() throws Exception {
        Member admin = createMember(MemberStatus.NORMAL, MemberRole.ADMIN);
        Cookie memberToken = new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(admin.getId(), MemberRole.MEMBER));

        // 관리자 API가 아직 없어 404지만, 권한 검사(403)에는 걸리지 않는다.
        mockMvc.perform(get("/api/admin/anything").cookie(memberToken))
                .andExpect(status().isNotFound());
    }

    // ---- 거래 제한 ----

    @Test
    void 거래_제한_회원도_조회는_할_수_있다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        mockMvc.perform(get("/api/members/me").cookie(accessCookie(member)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/test/trade/read").cookie(accessCookie(member)))
                .andExpect(status().isOk());
    }

    @Test
    void 거래_제한_회원의_표시_없는_쓰기_요청은_메서드마다_403이다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);
        Cookie cookie = accessCookie(member);

        List<MockHttpServletRequestBuilder> requests = List.of(
                post("/api/test/trade/default"),
                put("/api/test/trade/default"),
                patch("/api/test/trade/default"),
                delete("/api/test/trade/default"));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.cookie(cookie))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"))
                    .andExpect(jsonPath("$.error.message")
                            .value("이용이 제한된 계정입니다. 경매 등록, 입찰, 포인트 충전을 할 수 없습니다."));
        }
    }

    @Test
    void 거래_제한_회원도_허용_표시가_있는_쓰기_요청은_할_수_있다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        mockMvc.perform(post("/api/test/trade/allowed").cookie(accessCookie(member)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/test/trade-class/write").cookie(accessCookie(member)))
                .andExpect(status().isOk());
    }

    // 같은 토큰으로 상태만 바꿔 가며 호출해서, 상태가 요청마다 DB에서 다시 읽히고 즉시 반영되는지 확인한다.
    // 메서드를 바꿔 중복 요청 방지의 재반환(같은 주소·같은 본문 5초)에 걸리지 않게 한다.
    @Test
    void 같은_토큰이어도_상태가_바뀌면_다음_요청부터_바로_반영된다() throws Exception {
        Member member = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);
        Cookie cookie = accessCookie(member);

        mockMvc.perform(post("/api/test/trade/default").cookie(cookie)).andExpect(status().isOk());

        changeStatus(member, MemberStatus.RESTRICTED_TRADE);
        mockMvc.perform(put("/api/test/trade/default").cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_TRADE_RESTRICTED"));

        changeStatus(member, MemberStatus.RESTRICTED_LOGIN);
        mockMvc.perform(get("/api/members/me").cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));

        changeStatus(member, MemberStatus.NORMAL);
        mockMvc.perform(patch("/api/test/trade/default").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void 탈퇴_회원은_쓰기_요청도_403이다() throws Exception {
        Member member = createMember(MemberStatus.WITHDRAWN, MemberRole.MEMBER);

        mockMvc.perform(post("/api/test/trade/allowed").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }

    @Test
    void 차단된_회원이_관리자_경로를_호출해도_권한_부족이_아니라_상태_거부로_응답한다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.ADMIN);

        mockMvc.perform(get("/api/admin/anything").cookie(accessCookie(member)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }

    @Test
    void 거래_제한_회원도_HEAD로_조회할_수_있다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        mockMvc.perform(head("/api/test/trade/read").cookie(accessCookie(member)))
                .andExpect(status().isOk());
    }

    @Test
    void 인터페이스_메서드에_선언한_허용_표시도_인정된다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        mockMvc.perform(post("/api/test/trade-iface/iface").cookie(accessCookie(member)))
                .andExpect(status().isOk());
    }

    // 공개 쓰기 API의 허용 표시를 빼면 이 시험이 실패한다. 거래 제한 회원의 쿠키가 남아 있어도 가입과 이메일 인증을 시작할 수 있어야 한다.
    @Test
    void 거래_제한_쿠키가_남아_있어도_가입과_이메일_인증번호_요청은_막히지_않는다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);
        Cookie cookie = accessCookie(member);

        // 본문이 비어 있어 입력값 오류(400)가 되지만, 거래 제한(403)에 걸리지 않고 컨트롤러까지 도달했다는 뜻이다.
        mockMvc.perform(post("/api/members").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/email-codes").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/email-codes/verify").cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    // 인증 주체가 Long에서 AuthMember로 바뀌어도 중복 요청 방지가 로그인한 회원 단위로 요청을 구분하는지 실제 인증 흐름으로 확인한다.
    @Test
    void 중복_요청_방지는_로그인한_회원_단위로_구분한다() throws Exception {
        Member first = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);
        Member second = createMember(MemberStatus.NORMAL, MemberRole.MEMBER);

        // 같은 회원의 같은 요청은 두 번째가 재반환(Idempotent-Replay)이다.
        mockMvc.perform(post("/api/test/trade/default").cookie(accessCookie(first)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
        mockMvc.perform(post("/api/test/trade/default").cookie(accessCookie(first)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        // 다른 회원의 같은 주소·같은 본문 요청은 별개의 요청이다.
        mockMvc.perform(post("/api/test/trade/default").cookie(accessCookie(second)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
    }

    private void changeStatus(Member member, MemberStatus status) {
        ReflectionTestUtils.setField(member, "memberStatus", status);
        memberRepository.save(member);
    }

    @Test
    void 로그인하지_않은_쓰기_요청은_거래_제한이_아니라_401이다() throws Exception {
        mockMvc.perform(post("/api/test/trade/default"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void 거래_제한_회원도_로그아웃과_로그인과_재발급을_할_수_있다() throws Exception {
        Member member = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        // 이미 유효한 쿠키가 있는 상태에서 로그인
        MvcResult login = mockMvc.perform(post("/api/auth/login").cookie(accessCookie(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + member.getLoginId() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie refresh = new Cookie("REFRESH_TOKEN", login.getResponse().getCookie("REFRESH_TOKEN").getValue());

        // 유효한 액세스 토큰과 함께 재발급
        mockMvc.perform(post("/api/auth/refresh").cookie(accessCookie(member), refresh))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").cookie(accessCookie(member), refresh))
                .andExpect(status().isOk());
    }
}
