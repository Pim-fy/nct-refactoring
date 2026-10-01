package negocut.common.idempotency;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 상태 변경 요청의 바디를 여러 번 읽을 수 있게, 그리고 응답을 저장할 수 있게 요청·응답을 감싼다.
// 어떤 API를 제외할지(@SkipIdempotency)는 컨트롤러 정보가 필요해서 IdempotencyInterceptor가 판단한다.
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Set<String> TARGET_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    // 이보다 큰 요청은 메모리에 올리지 않고 보호 대상에서 뺀다.
    private static final long MAX_BODY_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!TARGET_METHODS.contains(request.getMethod())) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return true;
        }
        return request.getContentLengthLong() > MAX_BODY_BYTES;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            wrappedResponse.copyBodyToResponse();   // 저장용으로 모아 둔 응답 바디를 실제 응답으로 내보낸다.
        }
    }
}
