package negocut.auth.controller;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auth.dto.LoginRequest;
import negocut.auth.dto.LoginResponse;
import negocut.auth.dto.RefreshResponse;
import negocut.auth.security.AllowTradeRestricted;
import negocut.auth.security.AuthMember;
import negocut.auth.service.AuthService;
import negocut.auth.token.AuthCookies;
import negocut.auth.token.JwtTokenProvider;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;

// 세 API 모두 Set-Cookie로 쿠키를 내려주므로 응답을 저장해 재반환하는 중복 요청 방지에서 제외한다.
// 로그인·재발급·로그아웃은 거래 제한 회원도 할 수 있어야 하므로 @AllowTradeRestricted를 붙인다.
// 클래스가 아니라 메서드마다 붙인다. 이 클래스에 인증이 필요한 쓰기 API가 추가되면 기본대로 막히게 하기 위해서다.
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;
    private final JwtTokenProvider tokenProvider;

    @PostMapping("/login")
    @SkipIdempotency
    @AllowTradeRestricted
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request);

        // 로그인 유지를 선택하면 리프레시 토큰을 영속 쿠키로, 선택하지 않으면 세션 쿠키로 보낸다. (기능 명세서 1-3)
        Duration refreshMaxAge = result.keepLogin() ? tokenProvider.refreshValidity() : null;
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookies.access(result.accessToken(), tokenProvider.accessValidity()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookies.refresh(result.refreshToken(), refreshMaxAge).toString());

        return ApiResponse.success(result.body());
    }

    @PostMapping("/logout")
    @SkipIdempotency
    @AllowTradeRestricted
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal AuthMember member,
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            HttpServletResponse response) {

        authService.logout(member.memberId(), refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredAccess().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredRefresh().toString());
        return ApiResponse.success();
    }

    @PostMapping("/refresh")
    @SkipIdempotency
    @AllowTradeRestricted
    public ApiResponse<RefreshResponse> refresh(
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            HttpServletResponse response) {

        AuthService.RefreshResult result;
        try {
            result = authService.refresh(refreshToken);
        } catch (BusinessException e) {
            // 재발급은 공개 경로라 인증 필터의 차단 응답(쿠키 삭제)을 거치지 않는다. 정지·탈퇴 회원에게는 여기서도 쿠키를 지워,
            // 만료된 액세스 토큰과 남은 리프레시 토큰으로 같은 거부가 반복되지 않게 한다.
            if (e.getErrorCode() == ErrorCode.MEMBER_STATUS_NOT_ALLOWED) {
                response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredAccess().toString());
                response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredRefresh().toString());
            }
            throw e;
        }

        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookies.access(result.accessToken(), tokenProvider.accessValidity()).toString());
        return ApiResponse.success(result.body());
    }
}
