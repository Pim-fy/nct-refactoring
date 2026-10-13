package negocut.point.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import negocut.point.entity.PointBalance;

public interface PointBalanceRepository extends JpaRepository<PointBalance, Long> {

    Optional<PointBalance> findByMemberId(Long memberId);

    // 같은 회원의 충전·입찰이 겹쳐도 잔액이 어긋나지 않게 행을 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PointBalance b where b.member.id = :memberId")
    Optional<PointBalance> findByMemberIdForUpdate(Long memberId);
}
