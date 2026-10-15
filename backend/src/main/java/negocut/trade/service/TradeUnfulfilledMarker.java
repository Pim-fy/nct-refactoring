package negocut.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeRepository;

// 거래 한 건의 미이행 표시. 별도 빈이라 거래마다 트랜잭션이 나뉜다. (기능 명세서 3-8 미이행 처리)
@Component
@RequiredArgsConstructor
public class TradeUnfulfilledMarker {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TradeRepository tradeRepository;

    // 거래 행을 잠그고 상태·발송 기한·표시 여부를 다시 확인한다. 이미 발송되었거나 표시되었거나 기한 전이면 하지 않고 false를 돌려준다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean mark(Long tradeId) {
        Trade trade = tradeRepository.findByIdForUpdate(tradeId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (trade == null || trade.getTradeStatus() != TradeStatus.READY || trade.isUnfulfilled() || trade.getShipDueAt().isAfter(now)) {
            return false;
        }
        trade.markUnfulfilled(now);
        return true;
    }
}
