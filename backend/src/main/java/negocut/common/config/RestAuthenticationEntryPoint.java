package negocut.common.config;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import negocut.auth.security.JwtAuthenticationFilter;
import negocut.auth.token.AuthCookies;
import negocut.common.exception.ErrorCode;
import negocut.common.response.ApiResponse;
import negocut.common.response.ErrorResult;
import tools.jackson.databind.ObjectMapper;

// 인증이 필요한 경로에 인증되지 않은 요청이 오면 응답한다.
// 기본은 401이다. 단, 필터가 로그인 정지·탈퇴 회원의 요청이라고 표시했으면 403 MEMBER_STATUS_NOT_ALLOWED로 응답하고
// 인증 쿠키를 지운다. 이 회원은 로그아웃도 할 수 없어서, 같은 거부가 반복되지 않게 쿠키를 여기서 정리한다.
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final AuthCookies authCookies;

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException)
        throws IOException {

        boolean statusBlocked = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.MEMBER_STATUS_BLOCKED));
        ErrorCode errorCode = statusBlocked ? ErrorCode.MEMBER_STATUS_NOT_ALLOWED : ErrorCode.AUTHENTICATION_REQUIRED;

        ApiResponse<Void> body = ApiResponse.error(
            ErrorResult.of(errorCode.name(), errorCode.getMessage())
        );

        response.setStatus(errorCode.getHttpStatus().value());
        if (statusBlocked) {
            response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredAccess().toString());
            response.addHeader(HttpHeaders.SET_COOKIE, authCookies.expiredRefresh().toString());
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));

        // [비교용] body 변수를 따로 빼지 않았을 때
        // response.getWriter().write(
        //     objectMapper.writeValueAsString(
        //         ApiResponse.error(
        //             ErrorResult.of(errorCode.name(), errorCode.getMessage())
        //         )
        //     )
        // );
    }
}
