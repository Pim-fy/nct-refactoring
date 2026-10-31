package negocut.report.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import negocut.report.entity.ReportReason;
import negocut.report.entity.ReportTargetType;

// API 명세서 6-1 POST /api/reports. 상세 내용은 앞뒤 공백을 지우고, 부적절 행위·기타일 때 필수인지는 서비스가 확인한다.
public record ReportRequest(
        @NotNull(message = "신고 대상 유형을 선택해 주세요.")
        ReportTargetType targetType,
        @NotNull(message = "신고 대상을 지정해 주세요.")
        @Positive(message = "신고 대상을 확인해 주세요.")
        Long targetId,
        @NotNull(message = "신고 사유를 선택해 주세요.")
        ReportReason reportReason,
        @Size(max = 500, message = "상세 내용은 500자 이하로 입력해 주세요.")
        String detail) {

    private static final String EDGE_SPACES = "^[\\p{Z}\\s\\p{Cf}\\u3164\\u2800]+|[\\p{Z}\\s\\p{Cf}\\u3164\\u2800]+$";

    // 앞뒤 공백은 NBSP·전각 공백 같은 유니코드 공백과 제로폭·서식 문자(ZWSP·BOM 등)와 한글 채움 문자도 지운다. 비면 없는 것으로 본다.
    public ReportRequest {
        if (detail != null) {
            detail = detail.replaceAll(EDGE_SPACES, "");
            if (detail.isEmpty()) {
                detail = null;
            }
        }
    }
}
