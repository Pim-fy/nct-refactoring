package negocut.auction.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import negocut.auction.repository.AuctionRepository;

// 종료 시각이 지난 경매를 찾아 하나씩 종료 처리한다. (기능 명세서 3-4 경매 종료 처리, API 명세서 8장)
// 이 메서드는 트랜잭션 없이 실행하고, 경매마다 AuctionCloseProcessor가 별도 트랜잭션으로 처리한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionClosingService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 한 번에 읽는 경매 수. 남은 것은 다음 실행에서 이어서 처리한다.
    private static final int BATCH_SIZE = 100;

    private final AuctionRepository auctionRepository;
    private final AuctionCloseProcessor processor;

    // 이번 실행에서 처리한 경매 수를 돌려준다. 한 경매가 실패해도 로그를 남기고 나머지를 계속 처리하며, 실패한 경매는 다음 실행에서 다시 시도한다.
    public int closeExpired() {
        List<Long> ids = auctionRepository.findIdsToClose(LocalDateTime.now(ZONE), PageRequest.of(0, BATCH_SIZE));
        int closed = 0;
        for (Long id : ids) {
            try {
                if (processor.close(id)) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.error("[AuctionClosing] 경매 종료 처리에 실패했습니다. auctionId={}", id, e);
            }
        }
        return closed;
    }
}
