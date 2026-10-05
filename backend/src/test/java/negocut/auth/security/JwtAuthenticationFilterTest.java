package negocut.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.Cookie;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberAuthInfo;
import negocut.member.repository.MemberRepository;

// 필터를 DB 없이 단독으로 시험한다. 회원 상태 조회가 실패해도 요청이 오류로 끊기지 않고 "인증되지 않은 요청"으로 넘어가는지 확인한다.
class JwtAuthenticationFilterTest {

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(
            "filter-unit-test-signing-key-0123456789abcdef-0123456789", 30, 14);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, memberRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agreements");
        request.setCookies(new Cookie("ACCESS_TOKEN", tokenProvider.createAccessToken(7L, MemberRole.MEMBER)));
        return request;
    }

    private MemberAuthInfo info(MemberRole role, MemberStatus status) {
        MemberAuthInfo info = mock(MemberAuthInfo.class);
        when(info.getMemberRole()).thenReturn(role);
        when(info.getMemberStatus()).thenReturn(status);
        return info;
    }

    @Test
    void DB_조회가_실패하면_인증하지_않고_요청을_그대로_넘긴다() throws Exception {
        when(memberRepository.findAuthInfoById(anyLong())).thenThrow(new DataAccessResourceFailureException("db down"));
        MockHttpServletRequest request = requestWithToken();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();   // 다음 단계로 넘어갔다
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.MEMBER_STATUS_BLOCKED)).isNull();   // 정지로 오인하지 않는다
    }

    @Test
    void 회원이_없으면_인증하지_않고_넘긴다() throws Exception {
        when(memberRepository.findAuthInfoById(anyLong())).thenReturn(Optional.empty());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 로그인_정지_회원은_인증하지_않고_차단_표시만_남긴다() throws Exception {
        MemberAuthInfo stopped = info(MemberRole.MEMBER, MemberStatus.RESTRICTED_LOGIN);
        when(memberRepository.findAuthInfoById(anyLong())).thenReturn(Optional.of(stopped));
        MockHttpServletRequest request = requestWithToken();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.MEMBER_STATUS_BLOCKED)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void 정상_회원은_DB의_상태와_역할로_인증한다() throws Exception {
        MemberAuthInfo restricted = info(MemberRole.ADMIN, MemberStatus.RESTRICTED_TRADE);
        when(memberRepository.findAuthInfoById(anyLong())).thenReturn(Optional.of(restricted));

        // 필터가 체인 안에서 SecurityContext를 지우지 않으므로, 체인 호출 직전에 컨텍스트를 확인하는 체인을 쓴다.
        final AuthMember[] seen = new AuthMember[1];
        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), (req, res) ->
                seen[0] = (AuthMember) SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        assertThat(seen[0]).isEqualTo(new AuthMember(7L, MemberRole.ADMIN, MemberStatus.RESTRICTED_TRADE));
        verify(memberRepository).findAuthInfoById(7L);
    }
}
