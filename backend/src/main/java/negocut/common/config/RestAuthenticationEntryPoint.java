package negocut.common.config;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import negocut.common.exception.ErrorCode;
import negocut.common.response.ApiResponse;
import negocut.common.response.ErrorResult;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException authException)
        throws IOException {

        ErrorCode errorCode = ErrorCode.AUTHENTICATION_REQUIRED;
        
        ApiResponse<Void> body = ApiResponse.error(
            ErrorResult.of(errorCode.name(), errorCode.getMessage())
        );

        response.setStatus(errorCode.getHttpStatus().value());
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
