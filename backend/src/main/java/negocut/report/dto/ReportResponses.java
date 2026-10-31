package negocut.report.dto;

import java.time.OffsetDateTime;

import negocut.report.entity.Report;
import negocut.report.entity.ReportActionType;
import negocut.report.entity.ReportReason;
import negocut.report.entity.ReportStatus;
import negocut.report.entity.ReportTargetType;
import negocut.trade.dto.TradeSummaryResponse;

// API 명세서 6-1 신고 응답들. 처리 사유와 처리한 관리자는 담지 않는다. (기능 명세서 3-9)
public final class ReportResponses {

    private ReportResponses() {
    }

    // POST /api/reports
    public record Received(Long reportId, ReportStatus reportStatus) {
    }

    // GET /api/members/me/reports 의 항목. actionType은 처리 전에는 null이다.
    public record Mine(Long reportId, ReportTargetType targetType, String targetLabel, ReportReason reportReason,
            ReportStatus reportStatus, ReportActionType actionType, OffsetDateTime createdAt, OffsetDateTime processedAt) {

        public static Mine of(Report report, String targetLabel) {
            return new Mine(report.getId(), report.getTargetType(), targetLabel, report.getReportReason(), report.getReportStatus(),
                    report.getActionType(), TradeSummaryResponse.toOffset(report.getCreatedAt()),
                    TradeSummaryResponse.toOffset(report.getProcessedAt()));
        }
    }
}
