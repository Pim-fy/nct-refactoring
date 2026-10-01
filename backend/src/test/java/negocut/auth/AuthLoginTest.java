package negocut.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import jakarta.servlet.http.Cookie;
import negocut.auth.entity.RefreshToken;
import negocut.auth.repository.RefreshTokenRepository;
import negocut.auth.token.TokenHasher;
import negocut.common.idempotency.IdempotencyInterceptor;
import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginTest {

    private static final String PASSWORD = "abcd1234";

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Member createMember(MemberStatus status, MemberRole role) {
        String n = String.valueOf(System.nanoTime());
        String suffix = n.substring(n.length() - 8);
        Member member = Member.create("lg" + suffix, passwordEncoder.encode(PASSWORD), "nick" + suffix.substring(2),
                "l" + suffix + "@example.com", "010-0000-0000");
        ReflectionTestUtils.setField(member, "memberStatus", status);
        ReflectionTestUtils.setField(member, "memberRole", role);
        return memberRepository.save(member);
    }

    private Member createMember() {
        return createMember(MemberStatus.NORMAL, MemberRole.MEMBER);
    }

    private MvcResult login(String loginId, String password, boolean keepLogin) throws Exception {
        String body = "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\",\"isKeepLogin\":" + keepLogin + "}";
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String setCookie(MvcResult result, String name) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst().orElseThrow();
    }

    private String cookieValue(MvcResult result, String name) {
        return result.getResponse().getCookie(name).getValue();
    }

    private Cookie[] tokenCookies(MvcResult loginResult) {
        return new Cookie[] {
                new Cookie("ACCESS_TOKEN", cookieValue(loginResult, "ACCESS_TOKEN")),
                new Cookie("REFRESH_TOKEN", cookieValue(loginResult, "REFRESH_TOKEN"))
        };
    }

    @Test
    void 로그인하면_토큰이_HttpOnly_쿠키로만_전달되고_본문에는_토큰이_없다() throws Exception {
        Member member = createMember();

        MvcResult result = login(member.getLoginId(), PASSWORD, false);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"expiresIn\":1800").contains("\"memberId\":" + member.getId())
                .contains("\"memberRole\":\"MEMBER\"").contains("\"memberStatus\":\"NORMAL\"");
        assertThat(body).doesNotContain("accessToken").doesNotContain("refreshToken");

        String access = setCookie(result, "ACCESS_TOKEN");
        assertThat(access).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/;").contains("Max-Age=1800");

        // 로그인 유지를 선택하지 않으면 리프레시 토큰은 만료 시간이 없는 세션 쿠키다.
        String refresh = setCookie(result, "REFRESH_TOKEN");
        assertThat(refresh).contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/api/auth");
        assertThat(refresh).doesNotContain("Max-Age");
    }

    @Test
    void 로그인_유지를_선택하면_리프레시_토큰이_14일_영속_쿠키다() throws Exception {
        Member member = createMember();

        MvcResult result = login(member.getLoginId(), PASSWORD, true);

        assertThat(setCookie(result, "REFRESH_TOKEN")).contains("Max-Age=" + 14 * 24 * 60 * 60);
    }

    @Test
    void 리프레시_토큰은_원문이_아니라_해시로_저장된다() throws Exception {
        Member member = createMember();

        MvcResult result = login(member.getLoginId(), PASSWORD, false);
        String refreshToken = cookieValue(result, "REFRESH_TOKEN");

        List<RefreshToken> stored = refreshTokenRepository.findByMemberIdAndRevokedFalse(member.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getTokenHash()).isNotEqualTo(refreshToken).isEqualTo(TokenHasher.sha256(refreshToken));
    }

    @Test
    void 아이디가_없거나_비밀번호가_틀려도_같은_응답이다() throws Exception {
        Member member = createMember();

        MvcResult wrongPassword = login(member.getLoginId(), "wrongpass1", false);
        MvcResult unknownId = login("nobody" + System.nanoTime(), PASSWORD, false);

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownId.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getContentAsString()).contains("LOGIN_FAILED");
        assertThat(unknownId.getResponse().getContentAsString()).isEqualTo(wrongPassword.getResponse().getContentAsString());
        assertThat(wrongPassword.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void 매우_긴_비밀번호도_오류_없이_로그인_실패로_처리한다() throws Exception {
        Member member = createMember();

        MvcResult result = login(member.getLoginId(), "가".repeat(40) + "a1", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("LOGIN_FAILED");
    }

    @Test
    void 로그인_정지와_탈퇴_회원은_로그인할_수_없고_거래_제한_회원은_할_수_있다() throws Exception {
        Member stopped = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.MEMBER);
        Member withdrawn = createMember(MemberStatus.WITHDRAWN, MemberRole.MEMBER);
        Member tradeRestricted = createMember(MemberStatus.RESTRICTED_TRADE, MemberRole.MEMBER);

        for (Member blocked : List.of(stopped, withdrawn)) {
            MvcResult result = login(blocked.getLoginId(), PASSWORD, false);
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsString()).contains("MEMBER_STATUS_NOT_ALLOWED");
        }
        assertThat(login(tradeRestricted.getLoginId(), PASSWORD, false).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 비밀번호가_틀린_경우는_상태보다_자격_불일치가_먼저다() throws Exception {
        Member stopped = createMember(MemberStatus.RESTRICTED_LOGIN, MemberRole.MEMBER);

        MvcResult result = login(stopped.getLoginId(), "wrongpass1", false);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 아이디나_비밀번호가_비어_있으면_400이다() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void 같은_로그인_요청을_반복해도_중복_요청_방지에서_제외되어_매번_처리한다() throws Exception {
        Member member = createMember();

        MvcResult first = login(member.getLoginId(), PASSWORD, false);
        MvcResult second = login(member.getLoginId(), PASSWORD, false);

        assertThat(second.getResponse().getHeader(IdempotencyInterceptor.REPLAY_HEADER)).isNull();
        assertThat(cookieValue(second, "REFRESH_TOKEN")).isNotEqualTo(cookieValue(first, "REFRESH_TOKEN"));
        assertThat(refreshTokenRepository.findByMemberIdAndRevokedFalse(member.getId())).hasSize(2);
    }

    @Test
    void 액세스_토큰_쿠키로_인증하고_역할에_따라_접근이_갈린다() throws Exception {
        Member user = createMember();
        Member admin = createMember(MemberStatus.NORMAL, MemberRole.ADMIN);
        Cookie userAccess = new Cookie("ACCESS_TOKEN", cookieValue(login(user.getLoginId(), PASSWORD, false), "ACCESS_TOKEN"));
        Cookie adminAccess = new Cookie("ACCESS_TOKEN", cookieValue(login(admin.getLoginId(), PASSWORD, false), "ACCESS_TOKEN"));

        // 관리자 경로: 일반 회원은 403, 관리자는 권한을 통과해 (없는 경로라) 404
        mockMvc.perform(get("/api/admin/anything").cookie(userAccess)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/anything").cookie(adminAccess)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_리프레시_토큰이_폐기되고_쿠키가_지워진다() throws Exception {
        Member member = createMember();
        MvcResult loginResult = login(member.getLoginId(), PASSWORD, false);

        MvcResult logout = mockMvc.perform(post("/api/auth/logout").cookie(tokenCookies(loginResult)))
                .andExpect(status().isOk()).andReturn();

        assertThat(setCookie(logout, "ACCESS_TOKEN")).contains("Max-Age=0");
        assertThat(setCookie(logout, "REFRESH_TOKEN")).contains("Max-Age=0");
        assertThat(refreshTokenRepository.findByMemberIdAndRevokedFalse(member.getId())).isEmpty();

        // 폐기된 리프레시 토큰으로는 재발급할 수 없다.
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", cookieValue(loginResult, "REFRESH_TOKEN"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void 로그아웃은_현재_기기의_토큰만_폐기한다() throws Exception {
        Member member = createMember();
        MvcResult device1 = login(member.getLoginId(), PASSWORD, false);
        MvcResult device2 = login(member.getLoginId(), PASSWORD, false);

        mockMvc.perform(post("/api/auth/logout").cookie(tokenCookies(device1))).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", cookieValue(device2, "REFRESH_TOKEN"))))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인하지_않은_로그아웃은_401이다() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void 변조된_액세스_토큰은_인증되지_않는다() throws Exception {
        Member member = createMember();
        String token = cookieValue(login(member.getLoginId(), PASSWORD, false), "ACCESS_TOKEN");

        // 서명 부분 중간의 글자 하나를 바꾼다. (끝에 글자를 덧붙이는 것은 디코딩에서 무시되어 변조가 되지 않는다.)
        int signatureStart = token.lastIndexOf('.') + 1;
        int target = signatureStart + 10;
        char replacement = token.charAt(target) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, target) + replacement + token.substring(target + 1);
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("ACCESS_TOKEN", tampered)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("ACCESS_TOKEN", "not-a-jwt")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 리프레시_토큰으로_액세스_토큰을_재발급한다() throws Exception {
        Member member = createMember();
        MvcResult loginResult = login(member.getLoginId(), PASSWORD, false);

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", cookieValue(loginResult, "REFRESH_TOKEN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn();

        assertThat(refreshed.getResponse().getContentAsString()).doesNotContain("accessToken");
        assertThat(setCookie(refreshed, "ACCESS_TOKEN")).contains("HttpOnly").contains("Max-Age=1800");
        // 리프레시 토큰은 교체하지 않는다. (교체는 2차)
        assertThat(refreshed.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().anyMatch(h -> h.startsWith("REFRESH_TOKEN="))).isFalse();
    }

    @Test
    void 재발급은_쿠키가_없거나_토큰이_올바르지_않으면_401이다() throws Exception {
        Member member = createMember();
        MvcResult loginResult = login(member.getLoginId(), PASSWORD, false);
        String accessToken = cookieValue(loginResult, "ACCESS_TOKEN");

        mockMvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", "garbage")))
                .andExpect(status().isUnauthorized());
        // 액세스 토큰을 리프레시 토큰 자리에 넣어도 종류가 달라 거부된다.
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", accessToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인_후_정지된_회원은_재발급도_막힌다() throws Exception {
        Member member = createMember();
        MvcResult loginResult = login(member.getLoginId(), PASSWORD, false);

        ReflectionTestUtils.setField(member, "memberStatus", MemberStatus.RESTRICTED_LOGIN);
        memberRepository.save(member);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", cookieValue(loginResult, "REFRESH_TOKEN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBER_STATUS_NOT_ALLOWED"));
    }
}
