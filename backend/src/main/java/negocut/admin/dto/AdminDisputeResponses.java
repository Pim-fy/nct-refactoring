package negocut.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import negocut.trade.dto.TradeDetailResponse;
import negocut.trade.dto.TradeSummaryResponse;
import negocut.trade.entity.Dispute;
import negocut.trade.entity.DisputeJudgment;
import negocut.trade.entity.DisputeStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeDueType;
import negocut.trade.entity.TradeRole;
import negocut.trade.entity.TradeStatus;

// API 명세서 7-1 관리자 분쟁 관리 응답들. 관리자만 보므로 닉네임을 가리지 않는다.
public final class AdminDisputeResponses {

    private AdminDisputeResponses() {
    }

    private static TradeRole roleOf(Dispute dispute, Trade trade) {
        return dispute.getReporter().getId().equals(trade.getBuyer().getId()) ? TradeRole.BUYER : TradeRole.SELLER;
    }

    // GET /api/admin/disputes 의 항목. judgment는 판정 전에는 null이고, 보류 유지는 disputeStatus가 OPEN인 채 HOLD다.
    public record Item(Long disputeId, Long tradeId, String productName, BigDecimal tradePrice, String reporterNickname,
            TradeRole reporterRole, String reason, TradeStatus previousTradeStatus, DisputeStatus disputeStatus,
            DisputeJudgment judgment, OffsetDateTime createdAt) {

        public static Item of(Dispute dispute) {
            Trade trade = dispute.getTrade();
            return new Item(dispute.getId(), trade.getId(), trade.getAuctionResult().getAuction().getProduct().getName(),
                    trade.getTradePrice(), dispute.getReporter().getNickname(), roleOf(dispute, trade), dispute.getReason(),
                    dispute.getPreviousTradeStatus(), dispute.getDisputeStatus(), dispute.getJudgment(),
                    TradeSummaryResponse.toOffset(dispute.getCreatedAt()));
        }
    }

    // 거래 진행으로 판정했을 때 정해지는 기한. adjusted가 true이면 원래 기한 대신 복귀 시각부터 2시간으로 정해진다.
    public record ResumeDue(TradeDueType dueType, OffsetDateTime currentDueAt, OffsetDateTime resumedDueAt, boolean adjusted) {

        public static ResumeDue of(Trade.ResumeDue due) {
            return new ResumeDue(due.dueType(), TradeSummaryResponse.toOffset(due.currentDueAt()),
                    TradeSummaryResponse.toOffset(due.resumedDueAt()), due.adjusted());
        }
    }

    // 거래 상세의 분쟁. judgmentReason은 관리자에게만 보낸다. resumeDue는 처리 중인 분쟁에만 있고, 지금 거래 진행으로 판정하면 정해질 기한이다.
    public record DisputeInfo(Long disputeId, DisputeStatus disputeStatus, TradeRole reporterRole, String reporterNickname, String reason,
            TradeStatus previousTradeStatus, OffsetDateTime createdAt, DisputeJudgment judgment, String judgmentReason,
            OffsetDateTime judgedAt, ResumeDue resumeDue) {

        public static DisputeInfo of(Dispute dispute, Trade trade, ResumeDue resumeDue) {
            return new DisputeInfo(dispute.getId(), dispute.getDisputeStatus(), roleOf(dispute, trade), dispute.getReporter().getNickname(),
                    dispute.getReason(), dispute.getPreviousTradeStatus(), TradeSummaryResponse.toOffset(dispute.getCreatedAt()),
                    dispute.getJudgment(), dispute.getJudgmentReason(), TradeSummaryResponse.toOffset(dispute.getJudgedAt()), resumeDue);
        }
    }

    // GET /api/admin/trades/{id}. 5장 거래 상세와 같은 형식이되 myRole·availableActions는 없고 닉네임을 가리지 않으며, 배송 정보를 모두 담고
    // 분쟁은 모든 분쟁을 최근 것부터 담는다. cancelRequest는 처리 중인 요청이 있을 때만 있다.
    public record TradeDetail(Long tradeId, TradeStatus tradeStatus, BigDecimal tradePrice, String sellerNickname, String buyerNickname,
            TradeDetailResponse.AuctionInfo auction, TradeDetailResponse.Schedule schedule, TradeDetailResponse.DeliveryInfo delivery,
            TradeDetailResponse.CancelRequestInfo cancelRequest, List<DisputeInfo> disputes) {
    }

    // POST /api/admin/disputes/{id}/judge. 거래 진행 판정이면 dueType·dueAt·isDueAdjusted가 정해진 기한을 알려 주고, 그 밖에는 null이다.
    public record Judged(Long disputeId, DisputeStatus disputeStatus, DisputeJudgment judgment, Long tradeId, TradeStatus tradeStatus,
            OffsetDateTime judgedAt, TradeDueType dueType, OffsetDateTime dueAt, Boolean isDueAdjusted) {
    }
}
