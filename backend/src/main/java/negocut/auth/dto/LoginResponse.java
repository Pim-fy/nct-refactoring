package negocut.auth.dto;

import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;

// 토큰은 본문이 아니라 Set-Cookie로만 전달한다. expiresIn은 액세스 토큰의 유효 시간(초)이다.
public record LoginResponse(long expiresIn, MemberSummary member) {

    public record MemberSummary(Long memberId, String nickname, MemberRole memberRole, MemberStatus memberStatus) {
    }
}
