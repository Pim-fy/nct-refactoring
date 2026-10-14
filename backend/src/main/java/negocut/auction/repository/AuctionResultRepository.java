package negocut.auction.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.AuctionResult;

public interface AuctionResultRepository extends JpaRepository<AuctionResult, Long> {

    // 경매 상세의 낙찰 결과. 낙찰자를 함께 읽는다.
    @Query("select r from AuctionResult r join fetch r.winner where r.auction.id = :auctionId")
    Optional<AuctionResult> findByAuctionId(Long auctionId);
}
