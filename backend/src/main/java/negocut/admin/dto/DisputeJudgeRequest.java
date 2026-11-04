package negocut.admin.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.trade.entity.DisputeJudgment;

// API 명세서 7-1 POST /api/admin/disputes/{id}/judge. 판정 사유는 앞뒤 공백을 지우며, 읽을 수 있는 글자가 없으면 서비스가 400으로 거부한다.
// expectedJudgedAt은 관리자가 화면에서 본 마지막 판정 시각이다. 판정한 적이 없으면 보내지 않는다. 그사이 다른 관리자가 판정(보류 포함)했으면 서비스가 409로 거부한다.
public record DisputeJudgeRequest(
        @NotNull(message = "판정을 선택해 주세요.")
        DisputeJudgment judgment,
        @NotNull(message = "판정 사유를 입력해 주세요.")
        @Size(max = 500, message = "판정 사유는 500자 이하로 입력해 주세요.")
        String judgmentReason,
        OffsetDateTime expectedJudgedAt) {

    public DisputeJudgeRequest {
        judgmentReason = ReasonText.trim(judgmentReason);
    }
}
