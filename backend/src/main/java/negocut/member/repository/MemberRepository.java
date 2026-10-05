package negocut.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByLoginId(String loginId);

    // 인증 필터가 요청마다 호출한다. 역할과 상태 컬럼만 읽는다.
    Optional<MemberAuthInfo> findAuthInfoById(Long id);

    boolean existsByLoginId(String loginId);

    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);

    // 아이디 찾기·비밀번호 재설정의 인증번호 발송 전 계정 일치 확인. 탈퇴 회원은 대상에서 뺀다. (API-4)
    boolean existsByNicknameAndEmailAndMemberStatusNot(String nickname, String email, MemberStatus status);

    boolean existsByLoginIdAndEmailAndMemberStatusNot(String loginId, String email, MemberStatus status);
}
