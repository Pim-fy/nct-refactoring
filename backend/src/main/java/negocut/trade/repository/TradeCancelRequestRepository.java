package negocut.trade.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.trade.entity.TradeCancelRequest;

public interface TradeCancelRequestRepository extends JpaRepository<TradeCancelRequest, Long> {

    // 거래의 처리 중인 취소 요청. 거래당 한 건이다. 요청자를 함께 읽는다.
    @Query("select r from TradeCancelRequest r join fetch r.requester where r.trade.id = :tradeId and r.requestStatus = negocut.trade.entity.CancelRequestStatus.REQUESTED")
    Optional<TradeCancelRequest> findProcessingByTradeId(Long tradeId);

    // 이 거래에서 이 회원이 낸 취소 요청 수. 거절·종결된 요청도 센다.
    @Query("select count(r) from TradeCancelRequest r where r.trade.id = :tradeId and r.requester.id = :requesterId")
    long countByTradeIdAndRequesterId(Long tradeId, Long requesterId);

    // 요청 식별자가 이 거래의 것인지 함께 확인해 읽는다. 요청자를 함께 읽는다.
    @Query("select r from TradeCancelRequest r join fetch r.requester where r.id = :requestId and r.trade.id = :tradeId")
    Optional<TradeCancelRequest> findByIdAndTradeId(Long requestId, Long tradeId);
}
