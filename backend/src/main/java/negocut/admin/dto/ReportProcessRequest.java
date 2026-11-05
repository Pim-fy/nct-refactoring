package negocut.admin.dto;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.member.entity.MemberStatus;
import negocut.report.entity.ReportActionType;

// API 명세서 7-1 POST /api/admin/reports/{id}/process.
// restriction은 actionType이 MEMBER_RESTRICTED일 때만 보낸다. 제한할 회원은 서버가 신고 대상 유형으로 정하므로 받지 않는다.
// 사유는 앞뒤 공백을 지우며, 읽을 수 있는 글자가 없으면 서비스가 400으로 거부한다.
public record ReportProcessRequest(
        @NotNull(message = "처리 결과를 선택해 주세요.")
        ReportActionType actionType,
        @NotNull(message = "처리 사유를 입력해 주세요.")
        @Size(max = 500, message = "처리 사유는 500자 이하로 입력해 주세요.")
        String actionReason,
        @Valid
        Restriction restriction) {

    public ReportProcessRequest {
        actionReason = ReasonText.trim(actionReason);
    }

    // 회원 이용 제한 조치의 수준과 기간. 사유는 처리 사유(actionReason)를 그대로 쓴다.
    public record Restriction(
            @NotNull(message = "제한 수준을 선택해 주세요.")
            MemberStatus restrictionLevel,
            @NotNull(message = "영구 제한 여부를 선택해 주세요.")
            Boolean isPermanent,
            OffsetDateTime expiresAt) {
    }
}
