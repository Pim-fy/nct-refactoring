package negocut.support;

import java.util.concurrent.atomic.AtomicLong;

// 테스트마다 겹치지 않는 8자리 숫자를 만든다.
// System.nanoTime()의 뒤 8자리는 시계 해상도 때문에 연속 호출에서 같은 값이 나올 수 있어 순서대로 증가하는 번호를 쓴다.
public final class TestSuffix {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 100_000_000L);

    private TestSuffix() {
    }

    public static String next() {
        return String.format("%08d", SEQUENCE.incrementAndGet() % 100_000_000L);
    }
}
