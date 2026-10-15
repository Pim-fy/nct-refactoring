package negocut.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.service.PointService;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeRepository;

// 구매 확정과 포인트 지급. 두 단계를 서로 다른 트랜잭션으로 처리하려고 별도 빈으로 둔다. (기능 명세서 3-8 거래 상태 변경)
// 1) 구매 확정을 상태로 저장하고 커밋한다. 2) 이어서 포인트 지급과 완료 전환을 하나의 트랜잭션으로 처리한다.
// 2)가 실패해도 1)은 남아 거래는 구매 확정에 멈추고, 어떤 경우에도 중복 지급되지 않는다.
// 잠금 순서는 거래 행 → 낙찰자·판매자의 잔액 행(회원 번호 순서)이다. 회원 행은 먼저 잠그지 않는다. (기능 명세서 D-3)
@Component
@RequiredArgsConstructor
public class TradeConfirmProcessor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TradeRepository tradeRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final PointService pointService;

    // 낙찰자가 구매를 확정한다. 낙찰자만, 진행 상태에서만 할 수 있다. 이미 확정되었거나 완료된 거래는 중복 처리다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void confirmByBuyer(Long tradeId, Long memberId) {
        Trade trade = tradeRepository.findByIdForUpdate(tradeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        if (!trade.getBuyer().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);   // 구매 확정은 낙찰자만 할 수 있다. 판매자와 당사자가 아닌 회원은 권한이 없다.
        }
        if (trade.getTradeStatus() == TradeStatus.CONFIRMED || trade.getTradeStatus() == TradeStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.TRADE_ALREADY_PROCESSED);
        }
        if (trade.getTradeStatus() != TradeStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TRADE_STATUS_INVALID);
        }
        trade.confirm(LocalDateTime.now(ZONE), false);
    }

    // 발송 후 10일이 지나도록 구매 확정이 없는 거래를 시스템이 확정한다. 이미 처리되었거나 아직 기한 전이면 하지 않고 false를 돌려준다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean confirmAutomatically(Long tradeId) {
        Trade trade = tradeRepository.findByIdForUpdate(tradeId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (trade == null || trade.getTradeStatus() != TradeStatus.IN_PROGRESS
                || trade.getConfirmDueAt() == null || trade.getConfirmDueAt().isAfter(now)) {
            return false;
        }
        trade.confirm(now, true);
        return true;
    }

    // 구매 확정된 거래의 포인트를 지급하고 완료로 바꾼다. 지급과 완료 전환은 하나의 트랜잭션이다.
    // 거래 행을 잠근 뒤 상태를 다시 확인하므로 같은 거래를 동시에 처리해도 한 번만 지급된다. 이미 지급되었으면 false다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean pay(Long tradeId) {
        Trade trade = tradeRepository.findByIdForUpdate(tradeId).orElse(null);
        if (trade == null || trade.getTradeStatus() != TradeStatus.CONFIRMED) {
            return false;
        }

        Long buyerId = trade.getBuyer().getId();
        Long sellerId = trade.getSeller().getId();
        if (buyerId.equals(sellerId)) {
            throw new IllegalStateException("판매자와 낙찰자가 같은 거래입니다. tradeId=" + tradeId);   // 본인 경매 입찰 금지로 생길 수 없는 데이터다.
        }
        // 회원 번호 순서로 한 명씩 잠근다. 입찰과 같은 순서라 서로 교착 상태가 되지 않는다.
        PointBalance buyerBalance = null;
        PointBalance sellerBalance = null;
        for (Long memberId : List.of(Math.min(buyerId, sellerId), Math.max(buyerId, sellerId))) {
            PointBalance balance = pointBalanceRepository.findByMemberIdForUpdate(memberId)
                    .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + memberId));
            if (memberId.equals(buyerId)) {
                buyerBalance = balance;
            } else {
                sellerBalance = balance;
            }
        }

        pointService.payout(buyerBalance, trade.getBuyer(), sellerBalance, trade.getSeller(), trade.getTradePrice(), trade.getId());
        trade.complete(LocalDateTime.now(ZONE));
        return true;
    }
}
