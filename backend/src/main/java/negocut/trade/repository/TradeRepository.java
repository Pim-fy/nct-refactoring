package negocut.trade.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import negocut.trade.entity.Trade;

public interface TradeRepository extends JpaRepository<Trade, Long> {

    // 상태를 바꾸는 처리에서 거래 행을 잠그고 읽는다. 같은 거래의 처리는 이 잠금으로 한 건씩 진행된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trade t where t.id = :id")
    Optional<Trade> findByIdForUpdate(Long id);

    // 거래 상세에 필요한 낙찰·경매·상품·당사자를 함께 읽는다.
    @Query("select t from Trade t join fetch t.auctionResult r join fetch r.auction a join fetch a.product join fetch t.seller join fetch t.buyer where t.id = :id")
    Optional<Trade> findDetailById(Long id);

    // 발송 후 10일이 지나도록 구매 확정이 없는 거래. 자동 구매 확정 대상이며 확정 기한이 이른 것부터 읽는다.
    @Query("select t.id from Trade t where t.tradeStatus = negocut.trade.entity.TradeStatus.IN_PROGRESS and t.confirmDueAt <= :now order by t.confirmDueAt, t.id")
    List<Long> findIdsToAutoConfirm(LocalDateTime now, Pageable pageable);

    // 구매 확정되었는데 지급이 끝나지 않은 거래. 확정 시각이 이른 것부터 읽는다. 지급 재시도 대상이다.
    @Query("select t.id from Trade t where t.tradeStatus = negocut.trade.entity.TradeStatus.CONFIRMED order by t.confirmedAt, t.id")
    List<Long> findIdsToRetryPayout(Pageable pageable);

    // 낙찰 후 5일이 지나도록 발송이 없는데 아직 미이행 표시가 없는 거래. 발송 기한이 이른 것부터 읽는다.
    @Query("select t.id from Trade t where t.tradeStatus = negocut.trade.entity.TradeStatus.READY and t.unfulfilled = false and t.shipDueAt <= :now order by t.shipDueAt, t.id")
    List<Long> findIdsToMarkUnfulfilled(LocalDateTime now, Pageable pageable);
}
