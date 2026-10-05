package negocut.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

// 스프링 없이 필터만 시험한다. 요청 본문 크기 제한이 길이를 밝히지 않는 청크 전송에도 적용되는지 확인한다.
class IdempotencyFilterTest {

    private static final int OVER_LIMIT = IdempotencyFilter.MAX_BODY_BYTES + 10;

    private final IdempotencyFilter filter = new IdempotencyFilter(JsonMapper.builder().build());

    // Content-Length를 모르는 요청(청크 전송)을 흉내 낸다. MockHttpServletRequest는 본문이 있으면 길이를 자동으로 알려 주므로 -1로 가린다.
    private static class ChunkedRequest extends MockHttpServletRequest {
        ChunkedRequest(String method, byte[] body) {
            super(method, "/api/test");
            setContentType(MediaType.APPLICATION_JSON_VALUE);
            setContent(body);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }

    @Test
    void 길이를_밝히지_않은_큰_본문은_읽다가_413으로_거부한다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new ChunkedRequest("POST", new byte[OVER_LIMIT]), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("REQUEST_TOO_LARGE");
        assertThat(chain.getRequest()).as("컨트롤러까지 가지 않는다").isNull();
    }

    @Test
    void 선언된_길이가_큰_본문도_읽기_전에_413으로_거부한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[OVER_LIMIT]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 허용_크기의_본문은_통과하고_다시_읽을_수_있다() throws Exception {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new ChunkedRequest("POST", body), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isInstanceOf(CachedBodyHttpServletRequest.class);
        assertThat(((CachedBodyHttpServletRequest) chain.getRequest()).getCachedBody()).isEqualTo(body);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
    }

    @Test
    void 정확히_상한_크기의_본문은_통과한다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new ChunkedRequest("POST", new byte[IdempotencyFilter.MAX_BODY_BYTES]), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void GET과_멀티파트는_필터를_거치지_않는다() throws Exception {
        MockFilterChain getChain = new MockFilterChain();
        filter.doFilter(new ChunkedRequest("GET", new byte[OVER_LIMIT]), new MockHttpServletResponse(), getChain);
        assertThat(getChain.getRequest()).isNotNull().isNotInstanceOf(CachedBodyHttpServletRequest.class);

        ChunkedRequest multipart = new ChunkedRequest("POST", new byte[OVER_LIMIT]);
        multipart.setContentType("multipart/form-data; boundary=x");
        MockFilterChain multipartChain = new MockFilterChain();
        filter.doFilter(multipart, new MockHttpServletResponse(), multipartChain);
        assertThat(multipartChain.getRequest()).isNotNull().isNotInstanceOf(CachedBodyHttpServletRequest.class);
    }
}
