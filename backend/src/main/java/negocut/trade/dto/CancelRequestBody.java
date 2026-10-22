package negocut.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// API 명세서 5-2 POST /api/trades/{id}/cancel-requests. 앞뒤 공백은 지운다.
public record CancelRequestBody(
        @NotBlank(message = "취소 사유를 입력해 주세요.")
        @Size(max = 500, message = "취소 사유는 500자 이하로 입력해 주세요.")
        String reason) {

    private static final String EDGE_SPACES = "^[\\p{Z}\\s\\u200B-\\u200D\\u2060\\uFEFF]+|[\\p{Z}\\s\\u200B-\\u200D\\u2060\\uFEFF]+$";

    // 앞뒤 공백은 NBSP·전각 공백 같은 유니코드 공백과 제로폭 공백(ZWSP·BOM)도 지운다. (String.trim은 NBSP를 남긴다.)
    public CancelRequestBody {
        reason = reason == null ? null : reason.replaceAll(EDGE_SPACES, "");
    }
}
