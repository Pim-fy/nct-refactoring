package negocut.trade.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.trade.entity.Dispute;

public interface DisputeRepository extends JpaRepository<Dispute, Long> {

    // 거래에 처리 중인 분쟁이 있는지. 처리 중인 분쟁은 거래당 한 건이다.
    @Query("select count(d) > 0 from Dispute d where d.trade.id = :tradeId and d.disputeStatus = negocut.trade.entity.DisputeStatus.OPEN")
    boolean existsProcessingByTradeId(Long tradeId);

    // 이 거래에서 이 회원이 접수한 분쟁 수. 판정이 끝난 분쟁도 센다.
    @Query("select count(d) from Dispute d where d.trade.id = :tradeId and d.reporter.id = :reporterId")
    long countByTradeIdAndReporterId(Long tradeId, Long reporterId);

    // 거래의 가장 최근 분쟁. 접수자를 함께 읽는다.
    @Query("select d from Dispute d join fetch d.reporter where d.trade.id = :tradeId order by d.id desc limit 1")
    Optional<Dispute> findLatestByTradeId(Long tradeId);
}
