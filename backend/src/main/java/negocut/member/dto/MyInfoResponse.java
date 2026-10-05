package negocut.member.dto;

import java.math.BigDecimal;

import negocut.member.entity.Member;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;
import negocut.point.entity.PointBalance;

// API 명세서 1-2 GET /api/members/me
public record MyInfoResponse(
        Long memberId,
        String loginId,
        String nickname,
        String email,
        String phone,
        Long profileImageId,
        String profileImageUrl,
        MemberStatus memberStatus,
        MemberRole memberRole,
        Point point) {

    public record Point(BigDecimal totalPoint, BigDecimal reservedPoint, BigDecimal availablePoint) {
    }

    public static MyInfoResponse of(Member member, PointBalance balance) {
        Long imageId = member.getProfileImageId();
        return new MyInfoResponse(
                member.getId(),
                member.getLoginId(),
                member.getNickname(),
                member.getEmail(),
                member.getPhone(),
                imageId,
                imageId == null ? null : "/api/images/" + imageId,
                member.getMemberStatus(),
                member.getMemberRole(),
                new Point(
                        balance.getTotalPoint(),
                        balance.getReservedPoint(),
                        balance.getTotalPoint().subtract(balance.getReservedPoint())));   // 사용 가능 = 보유 − 예약
    }
}
