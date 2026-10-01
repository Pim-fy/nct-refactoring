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
import negocut.auth.service.AuthService;
import negocut.auth.token.AuthCookies;
import negocut.auth.token.JwtTokenProvider;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;

// 세 API 모두 Set-Cookie로 쿠키를 내려주므로 응답을 저장해 재반환하는 중복 요청 방지에서 제외한다.
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookies authCookies;
    private final JwtTokenProvider tokenProvider;

    @PostMapping("/login")
    @SkipIdempotency
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
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal Long memberId,
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            HttpServletResponse response) {

        authService.logout(memberId, refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredAccess().toString());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredRefresh().toString());
        return ApiResponse.success();
    }

    @PostMapping("/refresh")
    @SkipIdempotency
    public ApiResponse<RefreshResponse> refresh(
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
            HttpServletResponse response) {

        AuthService.RefreshResult result = authService.refresh(refreshToken);

        response.addHeader(HttpHeaders.SET_COOKIE,
                authCookies.access(result.accessToken(), tokenProvider.accessValidity()).toString());
        return ApiResponse.success(result.body());
    }
}
