package negocut.admin.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import negocut.member.entity.MemberStatus;

// API 명세서 7-1 POST /api/admin/members/{id}/restrictions.
// restrictionLevel은 RESTRICTED_TRADE 또는 RESTRICTED_LOGIN이어야 하고, 영구 제한이 아니면 지금보다 뒤의 expiresAt이 필요하다.
// 이미 제한 중인 회원은 관리자가 화면에서 확인한 현재 제한의 식별자(expectedRestrictionId)를 보내야 교체한다.
// 사유는 앞뒤 공백을 지우며, 읽을 수 있는 글자가 없으면 서비스가 400으로 거부한다.
public record MemberRestrictRequest(
        @NotNull(message = "제한 수준을 선택해 주세요.")
        MemberStatus restrictionLevel,
        @NotNull(message = "영구 제한 여부를 선택해 주세요.")
        Boolean isPermanent,
        OffsetDateTime expiresAt,
        @NotNull(message = "제한 사유를 입력해 주세요.")
        @Size(max = 500, message = "제한 사유는 500자 이하로 입력해 주세요.")
        String reason,
        Long expectedRestrictionId) {

    public MemberRestrictRequest {
        reason = ReasonText.trim(reason);
    }
}
