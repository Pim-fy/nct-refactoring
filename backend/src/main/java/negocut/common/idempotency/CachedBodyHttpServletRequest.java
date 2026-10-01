package negocut.common.idempotency;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

// 요청 바디를 한 번 읽어 두고 여러 번 읽을 수 있게 하는 래퍼.
// 인터셉터가 지문을 만들려고 바디를 읽은 뒤에도 컨트롤러가 같은 바디를 다시 읽어야 하기 때문에 필요하다.
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] getCachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("비동기 읽기는 지원하지 않습니다.");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding() != null ? getCharacterEncoding() : "UTF-8";
        try {
            return new BufferedReader(new InputStreamReader(getInputStream(), encoding));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
