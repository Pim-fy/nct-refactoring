package negocut.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// API 명세서 7-1 DELETE /api/admin/members/{id}/restrictions.
// 관리자가 화면에서 확인한 현재 제한의 식별자(restrictionId)와 같을 때만 해제한다. 사유는 앞뒤 공백을 지우며, 읽을 수 있는 글자가 없으면 서비스가 400으로 거부한다.
public record MemberReleaseRequest(
        Long restrictionId,
        @NotNull(message = "해제 사유를 입력해 주세요.")
        @Size(max = 500, message = "해제 사유는 500자 이하로 입력해 주세요.")
        String reason) {

    public MemberReleaseRequest {
        reason = ReasonText.trim(reason);
    }
}
