package negocut.auth.security;

import java.io.IOException;
import java.util.List;

import org.springframework.dao.DataAccessException;
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
import lombok.extern.slf4j.Slf4j;
import negocut.auth.token.AuthCookies;
import negocut.auth.token.JwtTokenProvider;
import negocut.member.entity.MemberStatus;
import negocut.member.repository.MemberAuthInfo;
import negocut.member.repository.MemberRepository;

// 요청의 액세스 토큰 쿠키가 유효하면 로그인한 회원으로 인증한다.
// 토큰이 유효해도 회원의 현재 상태와 역할은 요청마다 DB에서 읽어 쓴다. (기능 명세서 2-2)
//   - 회원이 없거나 DB 조회에 실패하면: 인증하지 않는다. 보호된 경로는 401로 응답한다.
//   - 로그인 정지·탈퇴 상태면: 인증하지 않고 사유만 요청에 표시한다. 보호된 경로에서는 EntryPoint가 이 표시를 보고 403으로 응답하고,
//     공개 경로(로그인, 약관 조회 등)는 쿠키가 남아 있어도 영향을 받지 않는다.
// 토큰이 없거나 만료·변조됐으면 아무것도 하지 않아 인증되지 않은 요청이 되고, 보호된 경로는 401로 응답한다.
// 컴포넌트로 등록하면 서블릿 필터로 한 번 더 실행되므로 SecurityConfig에서 직접 만들어 보안 필터 체인에만 넣는다.
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // 로그인 정지·탈퇴 회원의 요청임을 EntryPoint에 알리는 요청 속성 이름
    public static final String MEMBER_STATUS_BLOCKED = "negocut.auth.memberStatusBlocked";

    private final JwtTokenProvider tokenProvider;
    private final MemberRepository memberRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Cookie cookie = WebUtils.getCookie(request, AuthCookies.ACCESS_TOKEN);
        if (cookie != null && !cookie.getValue().isBlank()) {
            tokenProvider.parseAccessToken(cookie.getValue()).ifPresent(claims -> authenticate(request, claims.memberId()));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Long memberId) {
        MemberAuthInfo info;
        try {
            info = memberRepository.findAuthInfoById(memberId).orElse(null);
        } catch (DataAccessException e) {
            // DB 장애로 상태를 확인하지 못하면 인증하지 않고 넘긴다. 보호된 경로는 401이 되고(닫힌 쪽으로 실패),
            // 인증이 필요 없는 경로(로그인, 약관 조회)는 쿠키가 있다는 이유로 오류가 되지 않는다.
            log.warn("회원 상태를 조회하지 못해 인증하지 않고 넘깁니다. memberId={}", memberId, e);
            return;
        }
        if (info == null) {
            return;
        }

        MemberStatus status = info.getMemberStatus();
        if (status == MemberStatus.RESTRICTED_LOGIN || status == MemberStatus.WITHDRAWN) {
            request.setAttribute(MEMBER_STATUS_BLOCKED, Boolean.TRUE);
            return;
        }

        // 주체는 AuthMember다. 컨트롤러에서 @AuthenticationPrincipal AuthMember 로 받는다. 역할은 토큰이 아니라 DB 값을 쓴다.
        AuthMember principal = new AuthMember(memberId, info.getMemberRole(), status);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + info.getMemberRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
