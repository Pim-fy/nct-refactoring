package negocut.trade.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import negocut.trade.entity.Trade;

public interface TradeRepository extends JpaRepository<Trade, Long> {
}
