package negocut.point.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.point.entity.PointHistory;

public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {
}
