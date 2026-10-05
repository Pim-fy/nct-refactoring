package negocut.auth.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import negocut.auth.token.AuthCookies;
import negocut.auth.token.JwtTokenProvider;

// 요청의 액세스 토큰 쿠키가 유효하면 로그인한 회원으로 인증한다.
// 토큰이 없거나 만료·변조됐으면 아무것도 하지 않아 인증되지 않은 요청이 되고, 보호된 경로는 401로 응답한다.
// 컴포넌트로 등록하면 서블릿 필터로 한 번 더 실행되므로 SecurityConfig에서 직접 만들어 보안 필터 체인에만 넣는다.
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Cookie cookie = WebUtils.getCookie(request, AuthCookies.ACCESS_TOKEN);
        if (cookie != null && !cookie.getValue().isBlank()) {
            tokenProvider.parseAccessToken(cookie.getValue()).ifPresent(claims -> {
                // 주체는 회원 식별자(Long)다. 컨트롤러에서 @AuthenticationPrincipal Long 으로 받는다.
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        claims.memberId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        chain.doFilter(request, response);
    }
}
