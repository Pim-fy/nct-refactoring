package negocut.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByLoginId(String loginId);

    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);

    // 아이디 찾기·비밀번호 재설정의 인증번호 발송 전 계정 일치 확인. 탈퇴 회원은 대상에서 뺀다. (API-4)
    boolean existsByNicknameAndEmailAndMemberStatusNot(String nickname, String email, MemberStatus status);

    boolean existsByLoginIdAndEmailAndMemberStatusNot(String loginId, String email, MemberStatus status);
}
