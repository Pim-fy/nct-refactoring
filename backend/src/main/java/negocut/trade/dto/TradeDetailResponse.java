package negocut.trade.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import negocut.common.util.Nicknames;
import negocut.trade.entity.CancelRequestStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeAction;
import negocut.trade.entity.TradeCancelRequest;
import negocut.trade.entity.TradeDelivery;
import negocut.trade.entity.TradeRole;
import negocut.trade.entity.TradeStatus;

// API 명세서 5-2 GET /api/trades/{id}. 배송지와 운송장은 당사자에게만 보낸다.
// 취소 요청(cancelRequest)은 처리 중인 요청이 있을 때만 담고, 분쟁(dispute)은 분쟁 기능을 구현할 때 채우며 그전에는 null이다.
public record TradeDetailResponse(
        Long tradeId,
        TradeStatus tradeStatus,
        TradeRole myRole,
        BigDecimal tradePrice,
        Counterparty counterparty,
        AuctionInfo auction,
        Schedule schedule,
        DeliveryInfo delivery,
        CancelRequestInfo cancelRequest,
        Object dispute,
        List<TradeAction> availableActions) {

    public record Counterparty(String maskedNickname) {
    }

    // 처리 중인 취소 요청. requesterRole은 요청한 쪽의 역할이다.
    public record CancelRequestInfo(Long requestId, TradeRole requesterRole, String reason,
            CancelRequestStatus requestStatus, OffsetDateTime createdAt) {

        static CancelRequestInfo of(TradeCancelRequest request, Trade trade) {
            TradeRole requesterRole = request.getRequester().getId().equals(trade.getSeller().getId()) ? TradeRole.SELLER : TradeRole.BUYER;
            return new CancelRequestInfo(request.getId(), requesterRole, request.getReason(), request.getRequestStatus(),
                    TradeSummaryResponse.toOffset(request.getCreatedAt()));
        }
    }

    public record AuctionInfo(Long auctionId, String productName, String thumbnailUrl) {
    }

    public record Schedule(OffsetDateTime shipDueAt, OffsetDateTime confirmDueAt, OffsetDateTime confirmedAt,
            OffsetDateTime completedAt, boolean isAutoConfirmed, boolean isUnfulfilled, OffsetDateTime unfulfilledAt) {
    }

    // 배송지 입력 전에는 delivery 전체가 null이고, 입력 후 발송 전에는 운송장 관련 필드만 null이다.
    public record DeliveryInfo(String receiverName, String receiverPhone, String zipCode, String address,
            String addressDetail, String deliveryMessage, String courierName, String trackingNumber, OffsetDateTime shippedAt) {

        static DeliveryInfo of(TradeDelivery delivery) {
            return new DeliveryInfo(delivery.getReceiverName(), delivery.getReceiverPhone(), delivery.getZipCode(),
                    delivery.getAddress(), delivery.getAddressDetail(), delivery.getDeliveryMessage(),
                    delivery.getCourierName(), delivery.getTrackingNumber(), TradeSummaryResponse.toOffset(delivery.getShippedAt()));
        }
    }

    // delivery는 배송지를 입력하지 않았으면, cancelRequest는 처리 중인 취소 요청이 없으면 null이다.
    public static TradeDetailResponse of(Trade trade, TradeRole myRole, TradeDelivery delivery, TradeCancelRequest cancelRequest,
            Long thumbnailImageId, List<TradeAction> availableActions) {
        String counterpartNickname = myRole == TradeRole.SELLER ? trade.getBuyer().getNickname() : trade.getSeller().getNickname();
        return new TradeDetailResponse(
                trade.getId(),
                trade.getTradeStatus(),
                myRole,
                trade.getTradePrice(),
                new Counterparty(Nicknames.mask(counterpartNickname)),
                new AuctionInfo(
                        trade.getAuctionResult().getAuction().getId(),
                        trade.getAuctionResult().getAuction().getProduct().getName(),
                        thumbnailImageId == null ? null : "/api/images/" + thumbnailImageId),
                new Schedule(
                        TradeSummaryResponse.toOffset(trade.getShipDueAt()),
                        TradeSummaryResponse.toOffset(trade.getConfirmDueAt()),
                        TradeSummaryResponse.toOffset(trade.getConfirmedAt()),
                        TradeSummaryResponse.toOffset(trade.getCompletedAt()),
                        trade.isAutoConfirmed(),
                        trade.isUnfulfilled(),
                        TradeSummaryResponse.toOffset(trade.getUnfulfilledAt())),
                delivery == null ? null : DeliveryInfo.of(delivery),
                cancelRequest == null ? null : CancelRequestInfo.of(cancelRequest, trade),
                null,
                availableActions);
    }
}
