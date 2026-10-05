package negocut.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// 스프링 없이 저장소만 시험한다. 시계를 직접 움직여 만료를 확인한다.
class InMemoryRequestFingerprintStoreTest {

    private static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));

        void advance(Duration duration) {
            now.updateAndGet(t -> t.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final InMemoryRequestFingerprintStore store = new InMemoryRequestFingerprintStore(clock, Duration.ofSeconds(5));
    private final StoredResponse response = new StoredResponse(200, "application/json", "{}".getBytes());

    @Test
    void 처음_지문만_시작되고_같은_지문은_거부된다() {
        assertThat(store.tryStart("a")).isTrue();
        assertThat(store.tryStart("a")).isFalse();
        assertThat(store.tryStart("b")).isTrue();
    }

    @Test
    void 처리_중에는_응답이_없고_완료하면_응답을_돌려준다() {
        store.tryStart("a");
        assertThat(store.findResponse("a")).isEmpty();

        store.complete("a", response);
        assertThat(store.findResponse("a")).contains(response);
    }

    @Test
    void 완료_후_유효_시간이_지나면_같은_지문을_다시_처리할_수_있다() {
        store.tryStart("a");
        store.complete("a", response);

        clock.advance(Duration.ofSeconds(4));
        assertThat(store.tryStart("a")).isFalse();

        clock.advance(Duration.ofSeconds(2));
        assertThat(store.findResponse("a")).isEmpty();
        assertThat(store.tryStart("a")).isTrue();
    }

    @Test
    void 처리_중인_지문은_유효_시간이_지나도_상한까지는_중복으로_본다() {
        store.tryStart("a");

        clock.advance(Duration.ofSeconds(20));
        assertThat(store.tryStart("a")).isFalse();

        clock.advance(Duration.ofSeconds(11));
        assertThat(store.tryStart("a")).isTrue();
    }

    @Test
    void 지운_지문은_다시_시작할_수_있다() {
        store.tryStart("a");
        store.remove("a");

        assertThat(store.tryStart("a")).isTrue();
    }

    @Test
    void 동시에_같은_지문이_들어와도_한_요청만_시작된다() throws Exception {
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return store.tryStart("same");
            }));
        }
        ready.await();
        go.countDown();

        int started = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                started++;
            }
        }
        pool.shutdown();

        assertThat(started).isEqualTo(1);
    }
}
