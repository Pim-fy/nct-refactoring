package negocut.admin.dto;

import java.time.OffsetDateTime;

import negocut.member.entity.Member;
import negocut.member.entity.MemberRestriction;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.trade.dto.TradeSummaryResponse;

// API 명세서 7-1 관리자 회원 관리 응답들. 목록은 관리자만 보므로 이메일을 가리지 않는다.
public final class AdminMemberResponses {

    private AdminMemberResponses() {
    }

    // 목록 항목의 현재 제한. 제한 중인 회원에만 있다. 교체 확인 화면이 현재 제한 내용을 보여 주는 데 쓴다.
    public record CurrentRestriction(Long restrictionId, MemberStatus restrictionLevel, String reason, boolean isPermanent,
            OffsetDateTime startedAt, OffsetDateTime expiresAt) {

        public static CurrentRestriction of(MemberRestriction restriction) {
            return new CurrentRestriction(restriction.getId(), restriction.getRestrictionLevel(), restriction.getReason(),
                    restriction.isPermanent(), TradeSummaryResponse.toOffset(restriction.getStartedAt()),
                    TradeSummaryResponse.toOffset(restriction.getExpiresAt()));
        }
    }

    // GET /api/admin/members 의 항목. restriction은 제한 중이 아니면 null이다.
    public record Item(Long memberId, String loginId, String nickname, String email, MemberStatus memberStatus,
            MemberRole memberRole, OffsetDateTime createdAt, CurrentRestriction restriction) {

        public static Item of(Member member, MemberRestriction restriction) {
            return new Item(member.getId(), member.getLoginId(), member.getNickname(), member.getEmail(), member.getMemberStatus(),
                    member.getMemberRole(), TradeSummaryResponse.toOffset(member.getCreatedAt()),
                    restriction == null ? null : CurrentRestriction.of(restriction));
        }
    }

    // POST /api/admin/members/{id}/restrictions
    public record Restricted(Long memberId, MemberStatus memberStatus, Long restrictionId, OffsetDateTime startedAt,
            OffsetDateTime expiresAt) {
    }

    // DELETE /api/admin/members/{id}/restrictions
    public record Released(Long memberId, MemberStatus memberStatus, OffsetDateTime releasedAt) {
    }
}
