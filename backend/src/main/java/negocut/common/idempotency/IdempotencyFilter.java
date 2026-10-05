package negocut.common.idempotency;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import negocut.common.exception.ErrorCode;
import negocut.common.response.ApiResponse;
import negocut.common.response.ErrorResult;
import tools.jackson.databind.ObjectMapper;

// 상태 변경 요청의 바디를 여러 번 읽을 수 있게, 그리고 응답을 저장할 수 있게 요청·응답을 감싼다.
// 어떤 API를 제외할지(@SkipIdempotency)는 컨트롤러 정보가 필요해서 IdempotencyInterceptor가 판단한다.
//
// 본문은 메모리에 올려 두고 쓰므로 크기를 제한한다. 이 서비스의 JSON 요청은 1MB를 넘을 일이 없다.
// 선언된 길이(Content-Length)뿐 아니라 실제로 읽은 크기도 확인해서, 길이를 밝히지 않는 청크 전송도 막는다.
// 파일 업로드(멀티파트)는 별도 경로이므로 이 필터를 거치지 않는다.
@Component
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> TARGET_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!TARGET_METHODS.contains(request.getMethod())) {
            return true;
        }
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("multipart/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            rejectTooLarge(response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest;
        try {
            wrappedRequest = new CachedBodyHttpServletRequest(request, MAX_BODY_BYTES);
        } catch (RequestBodyTooLargeException e) {
            rejectTooLarge(response);
            return;
        }
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            wrappedResponse.copyBodyToResponse();   // 저장용으로 모아 둔 응답 바디를 실제 응답으로 내보낸다.
        }
    }

    private void rejectTooLarge(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.REQUEST_TOO_LARGE;
        ApiResponse<Void> body = ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage()));

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
