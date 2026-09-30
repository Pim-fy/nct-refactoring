package negocut.member.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.Member;

public interface MemberRepository extends JpaRepository<Member, Long> {

    boolean existsByLoginId(String loginId);

    boolean existsByNickname(String nickname);

    boolean existsByEmail(String email);
}
