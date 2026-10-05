package negocut.common.idempotency;

import java.io.IOException;

// 요청 본문이 허용 크기를 넘었다.
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long maxBytes) {
        super("요청 본문이 " + maxBytes + "바이트를 넘었습니다.");
    }
}
