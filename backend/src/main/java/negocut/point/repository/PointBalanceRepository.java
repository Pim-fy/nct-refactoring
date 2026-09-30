package negocut.point.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.point.entity.PointBalance;

public interface PointBalanceRepository extends JpaRepository<PointBalance, Long> {

    Optional<PointBalance> findByMemberId(Long memberId);
}
