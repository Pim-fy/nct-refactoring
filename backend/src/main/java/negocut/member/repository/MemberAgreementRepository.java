package negocut.member.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.MemberAgreement;

public interface MemberAgreementRepository extends JpaRepository<MemberAgreement, Long> {

    List<MemberAgreement> findByMemberId(Long memberId);
}
