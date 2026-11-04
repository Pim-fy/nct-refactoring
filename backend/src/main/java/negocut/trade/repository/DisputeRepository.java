package negocut.trade.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.trade.entity.Dispute;
import negocut.trade.entity.DisputeStatus;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    // 거래에 처리 중인 분쟁이 있는지. 처리 중인 분쟁은 거래당 한 건이다.
    @Query("select count(d) > 0 from Dispute d where d.trade.id = :tradeId and d.disputeStatus = negocut.trade.entity.DisputeStatus.OPEN")
    boolean existsProcessingByTradeId(Long tradeId);

    // 이 거래에서 이 회원이 접수한 분쟁 수. 판정이 끝난 분쟁도 센다.
    @Query("select count(d) from Dispute d where d.trade.id = :tradeId and d.reporter.id = :reporterId")
    long countByTradeIdAndReporterId(Long tradeId, Long reporterId);

    // 분쟁이 속한 거래의 식별자. 판정은 거래 행을 먼저 잠근 뒤 분쟁을 다시 읽어야 하므로, 분쟁 엔티티를 읽기 전에 거래 식별자만 읽는다.
    @Query("select d.trade.id from Dispute d where d.id = :disputeId")
    Optional<Long> findTradeIdById(Long disputeId);

    // 관리자 분쟁 목록. 상태가 null이면 모든 상태이고, 최근 접수가 먼저다. 목록에 필요한 거래·상품·접수자를 함께 읽는다.
    @Query(value = "select d from Dispute d join fetch d.trade t join fetch t.auctionResult r join fetch r.auction a join fetch a.product "
            + "join fetch t.buyer join fetch d.reporter where (:status is null or d.disputeStatus = :status)",
            countQuery = "select count(d) from Dispute d where (:status is null or d.disputeStatus = :status)")
    Page<Dispute> searchForAdmin(DisputeStatus status, Pageable pageable);

    // 거래의 모든 분쟁. 최근 분쟁이 먼저고 접수자를 함께 읽는다.
    @Query("select d from Dispute d join fetch d.reporter where d.trade.id = :tradeId order by d.id desc")
    List<Dispute> findAllByTradeId(Long tradeId);

    // 거래의 가장 최근 분쟁. 접수자를 함께 읽는다.
    @Query("select d from Dispute d join fetch d.reporter where d.trade.id = :tradeId order by d.id desc limit 1")
    Optional<Dispute> findLatestByTradeId(Long tradeId);
}
