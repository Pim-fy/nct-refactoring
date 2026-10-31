package negocut.member.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import negocut.member.entity.Member;
import negocut.member.entity.MemberStatus;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByLoginId(String loginId);

    // 어떤 회원이 현재 프로필 이미지로 쓰는 이미지인지. 프로필 이미지의 공개 여부를 판단한다.
    boolean existsByProfileImageId(Long profileImageId);

    // 같은 회원의 동시 요청을 한 줄로 세워야 하는 처리(이미지 업로드 한도 등)에서 회원 행을 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(Long id);

    // 인증 필터가 요청마다 호출한다. 역할과 상태 컬럼만 읽는다.
    Optional<MemberAuthInfo> findAuthInfoById(Long id);

    boolean existsByLoginId(String loginId);

    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);

    // 아이디 찾기·비밀번호 재설정의 인증번호 발송 전 계정 일치 확인. 탈퇴 회원은 대상에서 뺀다. (API-4)
    boolean existsByNicknameAndEmailAndMemberStatusNot(String nickname, String email, MemberStatus status);

    boolean existsByLoginIdAndEmailAndMemberStatusNot(String loginId, String email, MemberStatus status);

    // 관리자 회원 목록. 상태가 null이면 모든 상태이고, 검색어는 아이디·닉네임·이메일에 포함되는지 본다.
    // 검색어는 호출하는 쪽에서 %와 _를 '!'로 이스케이프해 앞뒤에 %를 붙여 넘긴다. (MySQL은 '\'를 이스케이프 문자로 쓸 수 없어 '!'를 쓴다.)
    @Query(value = "select m from Member m where (:status is null or m.memberStatus = :status) "
            + "and (m.loginId like :keyword escape '!' or m.nickname like :keyword escape '!' or m.email like :keyword escape '!')",
            countQuery = "select count(m) from Member m where (:status is null or m.memberStatus = :status) "
            + "and (m.loginId like :keyword escape '!' or m.nickname like :keyword escape '!' or m.email like :keyword escape '!')")
    Page<Member> searchForAdmin(MemberStatus status, String keyword, Pageable pageable);
}
