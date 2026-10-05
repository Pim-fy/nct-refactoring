package negocut.auth.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;

// 인증된 요청의 주체. 필터가 요청마다 DB에서 읽은 회원 식별자·역할·상태를 담아, 이후 단계가 상태 확인 때문에 회원을 다시 읽지 않게 한다.
// 컨트롤러에서는 @AuthenticationPrincipal AuthMember 로 받는다.
// getName()은 회원 식별자 문자열을 돌려줘서, 이를 사용자 구분에 쓰는 중복 요청 방지가 이전과 같은 값을 얻는다.
public record AuthMember(Long memberId, MemberRole role, MemberStatus status) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return String.valueOf(memberId);
    }
}
