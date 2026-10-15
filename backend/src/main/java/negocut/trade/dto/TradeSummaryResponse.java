package negocut.trade.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeRole;
import negocut.trade.entity.TradeStatus;

// API 명세서 5-2 내 거래 목록의 한 항목. dueAt은 현재 상태에 해당하는 기한이다(준비면 발송 기한, 진행이면 구매 확정 기한, 그 밖에는 null).
public record TradeSummaryResponse(
        Long tradeId,
        Long auctionId,
        String productName,
        String thumbnailUrl,
        BigDecimal tradePrice,
        TradeStatus tradeStatus,
        TradeRole myRole,
        boolean isUnfulfilled,
        OffsetDateTime dueAt,
        OffsetDateTime createdAt) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static TradeSummaryResponse of(Trade trade, TradeRole myRole, Long thumbnailImageId) {
        return new TradeSummaryResponse(
                trade.getId(),
                trade.getAuctionResult().getAuction().getId(),
                trade.getAuctionResult().getAuction().getProduct().getName(),
                thumbnailImageId == null ? null : "/api/images/" + thumbnailImageId,
                trade.getTradePrice(),
                trade.getTradeStatus(),
                myRole,
                trade.isUnfulfilled(),
                toOffset(dueAt(trade)),
                toOffset(trade.getCreatedAt()));
    }

    // 현재 상태에 해당하는 기한. 발송 전에는 구매 확정 기한을 내보내지 않는다.
    public static LocalDateTime dueAt(Trade trade) {
        return switch (trade.getTradeStatus()) {
            case READY -> trade.getShipDueAt();
            case IN_PROGRESS -> trade.getConfirmDueAt();
            default -> null;
        };
    }

    public static OffsetDateTime toOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ZONE).toOffsetDateTime();
    }
}
