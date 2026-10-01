package negocut.common.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 이 서버(JVM) 메모리의 동시성 맵에 지문을 둔다. 서버를 재시작하면 사라지고, 서버가 여러 대면 서로 공유되지 않는다.
// 유효 시간이 짧아(기본 5초) 재시작 손실은 문제가 되지 않는다.
@Component
public class InMemoryRequestFingerprintStore implements RequestFingerprintStore {

    // 처리 중인 요청이 끝나기 전에 지문이 만료되어 같은 요청이 한 번 더 처리되는 일을 막는 상한
    private static final Duration PROCESSING_LIMIT = Duration.ofSeconds(30);
    // 만료된 항목을 이 횟수의 등록마다 한 번씩 정리한다. 별도 스케줄러를 두지 않기 위한 방식이다.
    private static final int PURGE_INTERVAL = 256;

    private static class Entry {
        volatile Instant expiresAt;
        volatile StoredResponse response;

        Entry(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger startCount = new AtomicInteger();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public InMemoryRequestFingerprintStore(@Value("${app.idempotency.ttl-seconds:5}") long ttlSeconds) {
        this(Clock.systemDefaultZone(), Duration.ofSeconds(ttlSeconds));
    }

    // 시간을 바꿔 가며 만료를 시험할 수 있게 시계와 유효 시간을 받는다.
    public InMemoryRequestFingerprintStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public boolean tryStart(String fingerprint) {
        Instant now = clock.instant();
        boolean[] started = {false};

        // compute는 같은 키에 대해 원자적으로 실행되므로 동시에 들어와도 한 요청만 새 항목을 만든다.
        entries.compute(fingerprint, (key, existing) -> {
            if (existing == null || existing.isExpired(now)) {
                started[0] = true;
                return new Entry(now.plus(PROCESSING_LIMIT));
            }
            return existing;
        });

        if (startCount.incrementAndGet() % PURGE_INTERVAL == 0) {
            purgeExpired(now);
        }
        return started[0];
    }

    @Override
    public Optional<StoredResponse> findResponse(String fingerprint) {
        Entry entry = entries.get(fingerprint);
        if (entry == null || entry.isExpired(clock.instant())) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.response);
    }

    @Override
    public void complete(String fingerprint, StoredResponse response) {
        Entry entry = entries.get(fingerprint);
        if (entry != null) {
            entry.response = response;
            entry.expiresAt = clock.instant().plus(ttl);   // 완료 시점부터 유효 시간을 센다.
        }
    }

    @Override
    public void remove(String fingerprint) {
        entries.remove(fingerprint);
    }

    // 시험에서 저장소를 비우는 용도
    public void clear() {
        entries.clear();
    }

    private void purgeExpired(Instant now) {
        entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }
}
