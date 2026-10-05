package negocut.auth.token;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// 액세스·리프레시 토큰을 담는 HttpOnly 쿠키를 만든다. 프런트엔드는 토큰 값을 읽거나 저장하지 않는다. (기능 명세서 1-3)
@Component
public class AuthCookies {

    public static final String ACCESS_TOKEN = "ACCESS_TOKEN";
    public static final String REFRESH_TOKEN = "REFRESH_TOKEN";

    // 리프레시 토큰은 재발급·로그아웃 요청에만 실리도록 경로를 좁힌다.
    private static final String ACCESS_PATH = "/";
    private static final String REFRESH_PATH = "/api/auth";

    private final boolean secure;

    public AuthCookies(@Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public ResponseCookie access(String token, Duration maxAge) {
        return base(ACCESS_TOKEN, token, ACCESS_PATH).maxAge(maxAge).build();
    }

    // maxAge가 null이면 세션 쿠키(브라우저를 닫으면 사라짐)가 된다.
    public ResponseCookie refresh(String token, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = base(REFRESH_TOKEN, token, REFRESH_PATH);
        if (maxAge != null) {
            builder.maxAge(maxAge);
        }
        return builder.build();
    }

    public ResponseCookie expiredAccess() {
        return base(ACCESS_TOKEN, "", ACCESS_PATH).maxAge(Duration.ZERO).build();
    }

    public ResponseCookie expiredRefresh() {
        return base(REFRESH_TOKEN, "", REFRESH_PATH).maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path);
    }
}
