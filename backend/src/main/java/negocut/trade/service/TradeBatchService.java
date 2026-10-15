package negocut.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import negocut.trade.repository.TradeRepository;

// 시스템이 거래마다 처리하는 일: 자동 구매 확정과 미이행 표시. (기능 명세서 3-8, API 명세서 8장)
// 이 클래스의 메서드는 트랜잭션 없이 실행하고, 거래마다 별도 트랜잭션으로 처리한다. 한 거래의 실패가 다른 거래를 되돌리지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeBatchService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 한 번에 읽는 거래 수. 남은 것은 다음 실행에서 이어서 처리한다.
    private static final int BATCH_SIZE = 100;

    private final TradeRepository tradeRepository;
    private final TradeConfirmProcessor confirmProcessor;
    private final TradeUnfulfilledMarker unfulfilledMarker;

    // 발송 후 10일이 지나도록 구매 확정이 없는 거래를 자동으로 구매 확정하고 포인트를 지급한다. 지급까지 끝낸 거래 수를 돌려준다.
    // 구매 확정과 지급은 따로 커밋되므로, 지급이 실패한 거래는 구매 확정에 멈춘다. 그런 거래는 retryPayouts가 이어서 지급한다.
    public int autoConfirmDue() {
        List<Long> ids = tradeRepository.findIdsToAutoConfirm(LocalDateTime.now(ZONE), PageRequest.of(0, BATCH_SIZE));
        int confirmed = 0;
        for (Long id : ids) {
            try {
                if (confirmProcessor.confirmAutomatically(id)) {
                    confirmProcessor.pay(id);   // 실패하면 예외로 빠져 집계하지 않고, 구매 확정에 멈춘 채 retryPayouts가 이어서 처리한다.
                    confirmed++;
                }
            } catch (RuntimeException e) {
                log.error("[TradeBatch] 자동 구매 확정 처리에 실패했습니다. tradeId={}", id, e);
            }
        }
        return confirmed;
    }

    // 구매 확정되었는데 지급이 끝나지 않은 거래(지급 중 장애로 멈춘 거래)의 포인트 지급을 이어서 처리한다. 지급까지 끝낸 거래 수를 돌려준다.
    // pay는 거래 행을 잠근 뒤 구매 확정 상태를 다시 확인하므로, 구매 확정 직후의 지급이나 다른 실행과 겹쳐도 한 번만 지급된다.
    public int retryPayouts() {
        List<Long> ids = tradeRepository.findIdsToRetryPayout(PageRequest.of(0, BATCH_SIZE));
        int paid = 0;
        for (Long id : ids) {
            try {
                if (confirmProcessor.pay(id)) {
                    paid++;
                }
            } catch (RuntimeException e) {
                log.error("[TradeBatch] 멈춘 거래의 포인트 지급 재시도에 실패했습니다. tradeId={}", id, e);
            }
        }
        return paid;
    }

    // 낙찰 후 5일이 지나도록 발송 등록이 없는 거래에 미이행 표시를 남긴다. 거래 상태는 바꾸지 않는다. 처리한 거래 수를 돌려준다.
    public int markUnfulfilledDue() {
        List<Long> ids = tradeRepository.findIdsToMarkUnfulfilled(LocalDateTime.now(ZONE), PageRequest.of(0, BATCH_SIZE));
        int marked = 0;
        for (Long id : ids) {
            try {
                if (unfulfilledMarker.mark(id)) {
                    marked++;
                }
            } catch (RuntimeException e) {
                log.error("[TradeBatch] 미이행 표시에 실패했습니다. tradeId={}", id, e);
            }
        }
        return marked;
    }
}
