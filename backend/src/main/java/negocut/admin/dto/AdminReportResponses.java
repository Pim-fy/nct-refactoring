package negocut.admin.dto;

import java.time.OffsetDateTime;

import negocut.auction.entity.AuctionStatus;
import negocut.member.entity.MemberStatus;
import negocut.report.entity.Report;
import negocut.report.entity.ReportActionType;
import negocut.report.entity.ReportReason;
import negocut.report.entity.ReportStatus;
import negocut.report.entity.ReportTargetType;
import negocut.trade.dto.TradeSummaryResponse;

// API 명세서 7-1 관리자 신고 관리 응답들. 관리자만 보므로 신고자 닉네임을 가리지 않는다.
public final class AdminReportResponses {

    private AdminReportResponses() {
    }

    // GET /api/admin/reports 의 항목. 처리 전에는 actionType·actionReason·processedAt이 null이다. (처리 결과 필드는 명세에 없던 추가 필드)
    public record Item(Long reportId, ReportTargetType targetType, Long targetId, String targetLabel, String reporterNickname,
            ReportReason reportReason, String detail, ReportStatus reportStatus, ReportActionType actionType, String actionReason,
            OffsetDateTime createdAt, OffsetDateTime processedAt) {

        public static Item of(Report report, String targetLabel) {
            return new Item(report.getId(), report.getTargetType(), report.getTargetId(), targetLabel,
                    report.getReporter().getNickname(), report.getReportReason(), report.getDetail(), report.getReportStatus(),
                    report.getActionType(), report.getActionReason(), TradeSummaryResponse.toOffset(report.getCreatedAt()),
                    TradeSummaryResponse.toOffset(report.getProcessedAt()));
        }
    }

    // 신고 처리로 함께 수행한 조치. isAlreadyApplied는 조치 대상이 이미 그 상태여서 신고만 처리 완료로 남겼다는 뜻이다.
    public sealed interface AppliedAction permits AuctionAction, MemberAction {
    }

    public record AuctionAction(Long auctionId, AuctionStatus auctionStatus, boolean isAlreadyApplied) implements AppliedAction {
    }

    // restrictionId는 이 처리로 건 제한 또는 이미 걸려 있던 현재 제한의 식별자다.
    public record MemberAction(Long memberId, MemberStatus memberStatus, Long restrictionId, boolean isAlreadyApplied)
            implements AppliedAction {
    }

    // POST /api/admin/reports/{id}/process. 반려이면 appliedAction은 null이다.
    public record Processed(Long reportId, ReportStatus reportStatus, ReportActionType actionType, AppliedAction appliedAction) {
    }
}
