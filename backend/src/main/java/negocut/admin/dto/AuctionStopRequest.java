package negocut.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// API 명세서 7-1 POST /api/admin/auctions/{id}/stop. 사유는 앞뒤 공백을 지우며, 읽을 수 있는 글자가 없으면 서비스가 400으로 거부한다.
public record AuctionStopRequest(
        @NotNull(message = "중단 사유를 입력해 주세요.")
        @Size(max = 500, message = "중단 사유는 500자 이하로 입력해 주세요.")
        String stoppedReason) {

    public AuctionStopRequest {
        stoppedReason = ReasonText.trim(stoppedReason);
    }
}
