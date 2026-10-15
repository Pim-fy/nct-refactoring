package negocut.trade.service;

import java.util.function.IntSupplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 거래 스케줄러. 1분마다 자동 구매 확정과 미이행 표시를 처리한다. (API 명세서 8장)
// 이전 실행이 끝난 뒤 1분을 기다리므로 실행이 겹치지 않는다. app.scheduler.enabled=false로 끌 수 있다. 테스트는 끄고 서비스를 직접 부른다.
@Slf4j
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class TradeScheduler {

    private final TradeBatchService batchService;

    @Scheduled(initialDelayString = "${app.scheduler.trade-initial-delay-ms:7000}",
            fixedDelayString = "${app.scheduler.trade-delay-ms:60000}")
    public void processTrades() {
        // 한 처리가 예외로 끝나도 나머지 처리는 이어서 한다.
        int confirmed = run("자동 구매 확정", () -> batchService.autoConfirmDue());
        int retried = run("지급 재시도", () -> batchService.retryPayouts());
        int marked = run("미이행 표시", () -> batchService.markUnfulfilledDue());
        if (confirmed > 0 || retried > 0 || marked > 0) {
            log.info("[TradeBatch] 자동 구매 확정 {}건, 멈춘 지급 재시도 {}건, 미이행 표시 {}건을 처리했습니다.", confirmed, retried, marked);
        }
    }

    private int run(String name, IntSupplier task) {
        try {
            return task.getAsInt();
        } catch (RuntimeException e) {
            log.error("[TradeBatch] {} 처리 중 오류가 발생했습니다.", name, e);
            return 0;
        }
    }
}
