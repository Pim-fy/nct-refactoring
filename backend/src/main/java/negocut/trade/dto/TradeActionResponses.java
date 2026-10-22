package negocut.trade.dto;

import java.time.OffsetDateTime;
import java.util.List;

import negocut.point.dto.PointResponse;
import negocut.trade.entity.CancelRequestStatus;
import negocut.trade.entity.TradeAction;
import negocut.trade.entity.TradeStatus;

// API 명세서 5-2 거래 상태 변경 응답들. 처리 후의 상태와 다음에 할 수 있는 행동을 돌려준다.
public final class TradeActionResponses {

    private TradeActionResponses() {
    }

    // PUT /api/trades/{id}/delivery
    public record DeliverySaved(Long tradeId, TradeStatus tradeStatus, List<TradeAction> availableActions) {
    }

    // POST /api/trades/{id}/shipment
    public record ShipmentRegistered(Long tradeId, TradeStatus tradeStatus, OffsetDateTime shippedAt,
            OffsetDateTime confirmDueAt, List<TradeAction> availableActions) {
    }

    // POST /api/trades/{id}/cancel-requests
    public record CancelRequested(Long requestId, CancelRequestStatus requestStatus) {
    }

    // POST /api/trades/{id}/cancel-requests/{requestId}/accept. point는 반환을 받는 쪽(낙찰자)에게만 담고 판매자 응답에서는 null이다.
    public record CancelAccepted(Long tradeId, TradeStatus tradeStatus, CancelRequestStatus requestStatus, PointResponse point) {
    }

    // POST /api/trades/{id}/cancel-requests/{requestId}/reject
    public record CancelRejected(Long tradeId, TradeStatus tradeStatus, CancelRequestStatus requestStatus) {
    }

    // POST /api/trades/{id}/confirm. 정상 처리되면 완료이고, 지급이 실패해 구매 확정에 멈췄으면 그 상태와 completedAt null이 돌아온다.
    public record PurchaseConfirmed(Long tradeId, TradeStatus tradeStatus, OffsetDateTime confirmedAt,
            OffsetDateTime completedAt, List<TradeAction> availableActions) {
    }
}
