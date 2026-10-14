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
    // 입찰은 입찰자와 직전 최고 입찰자의 잔액을 함께 바꾸므로, 이 메서드를 회원 번호 순서로 한 명씩 불러 항상 같은 순서로 잠근다.
    // (서로 다른 경매의 입찰이 같은 두 회원을 반대 순서로 잠가 교착 상태가 되는 것을 막는다.)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PointBalance b where b.member.id = :memberId")
    Optional<PointBalance> findByMemberIdForUpdate(Long memberId);
}
