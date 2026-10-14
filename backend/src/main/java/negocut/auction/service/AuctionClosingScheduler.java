package negocut.auction.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 경매 종료 스케줄러. 1분마다 종료 시각이 지난 경매를 처리한다. (API 명세서 8장)
// 이전 실행이 끝난 뒤 1분을 기다리므로 실행이 겹치지 않는다. 앱이 꺼져 있던 동안 놓친 경매는 시작 뒤 첫 실행에서 처리한다.
// app.scheduler.enabled=false로 끌 수 있다. 테스트는 끄고 종료 처리 서비스를 직접 부른다.
@Slf4j
@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuctionClosingScheduler {

    private final AuctionClosingService closingService;

    @Scheduled(initialDelayString = "${app.scheduler.auction-closing-initial-delay-ms:5000}",
            fixedDelayString = "${app.scheduler.auction-closing-delay-ms:60000}")
    public void closeExpiredAuctions() {
        int closed = closingService.closeExpired();
        if (closed > 0) {
            log.info("[AuctionClosing] 경매 {}건을 종료 처리했습니다.", closed);
        }
    }
}
