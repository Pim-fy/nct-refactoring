package negocut.common.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import negocut.common.exception.ErrorCode;
import negocut.common.response.ApiResponse;
import negocut.common.response.ErrorResult;
import tools.jackson.databind.ObjectMapper;

// 같은 사용자가 같은 요청을 짧은 시간 안에 반복해서 보내는 경우(버튼 연타, 중복 전송)를 걸러 낸다.
// 기존 DB 방어(유니크 제약, 조건부 갱신, 상태 검사)를 대체하지 않고, 그 앞에 얹는 추가 방어층이다.
//
// 지문 = SHA-256( 식별자 | 메서드 | URI | 쿼리 | 바디 해시 )
//   식별자: 로그인한 사용자면 사용자, 아니면 접속 IP
//
// 처음 오는 요청은 그대로 처리하고, 성공(2xx)하면 응답을 저장한다.
// 같은 지문이 다시 오면 저장된 응답을 그대로 돌려주고, 아직 처리 중이면 409로 알린다.
// 실패(2xx가 아닌 응답)와 예외는 저장하지 않아서 같은 요청을 다시 시도할 수 있다.
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String REPLAY_HEADER = "Idempotent-Replay";

    private static final Set<String> TARGET_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_STORED_BODY_BYTES = 64 * 1024;
    private static final String ATTR_FINGERPRINT = IdempotencyInterceptor.class.getName() + ".fingerprint";

    private final RequestFingerprintStore store;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!TARGET_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod) || isSkipped(handlerMethod)) {
            return true;
        }
        // 필터가 바디를 캐싱하지 않은 요청(멀티파트, 너무 큰 요청)은 지문을 만들 수 없어 보호하지 않는다.
        if (!(request instanceof CachedBodyHttpServletRequest cachedRequest)) {
            return true;
        }

        String fingerprint = fingerprint(request, cachedRequest.getCachedBody());

        if (store.tryStart(fingerprint)) {
            request.setAttribute(ATTR_FINGERPRINT, fingerprint);
            return true;
        }

        Optional<StoredResponse> stored = store.findResponse(fingerprint);
        if (stored.isPresent()) {
            replay(response, stored.get());
        } else {
            writeProcessing(response);
        }
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {

        String fingerprint = (String) request.getAttribute(ATTR_FINGERPRINT);
        if (fingerprint == null) {
            return;
        }

        ContentCachingResponseWrapper wrapper = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        int status = response.getStatus();
        boolean success = ex == null && status >= 200 && status < 300;

        if (!success || wrapper == null || wrapper.getContentSize() > MAX_STORED_BODY_BYTES) {
            store.remove(fingerprint);   // 실패했거나 저장할 수 없으면 같은 요청을 다시 처리할 수 있게 한다.
            return;
        }
        store.complete(fingerprint, new StoredResponse(status, response.getContentType(), wrapper.getContentAsByteArray()));
    }

    private boolean isSkipped(HandlerMethod handlerMethod) {
        return handlerMethod.hasMethodAnnotation(SkipIdempotency.class)
                || handlerMethod.getBeanType().isAnnotationPresent(SkipIdempotency.class);
    }

    private String fingerprint(HttpServletRequest request, byte[] body) {
        String query = request.getQueryString() == null ? "" : request.getQueryString();
        return sha256(identifier(request) + "|" + request.getMethod() + "|" + request.getRequestURI()
                + "|" + query + "|" + sha256(body));
    }

    private String identifier(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "user:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader(REPLAY_HEADER, "true");
        response.getOutputStream().write(stored.body());
    }

    private void writeProcessing(HttpServletResponse response) throws IOException {
        ErrorCode errorCode = ErrorCode.IDEMPOTENCY_PROCESSING;
        ApiResponse<Void> body = ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage()));

        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
