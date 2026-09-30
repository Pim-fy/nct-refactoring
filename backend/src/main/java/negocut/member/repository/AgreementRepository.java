package negocut.member.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.member.entity.Agreement;

public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    // 시행 중인 약관: 사용 여부가 켜져 있고 시행 시각이 지난 약관 (기능 명세서 3-1)
    List<Agreement> findByActiveTrueAndEffectiveAtLessThanEqual(LocalDateTime now);
}
