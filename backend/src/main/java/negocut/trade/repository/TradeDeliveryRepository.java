package negocut.trade.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.trade.entity.TradeDelivery;

public interface TradeDeliveryRepository extends JpaRepository<TradeDelivery, Long> {

    Optional<TradeDelivery> findByTradeId(Long tradeId);
}
