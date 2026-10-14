package negocut.auction.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.Bid;

public interface BidRepository extends JpaRepository<Bid, Long> {

    // 입찰 내역. 높은 금액이 먼저이고, 같은 금액이면 나중 입찰이 먼저다. 입찰자를 함께 읽는다.
    @Query(value = "select b from Bid b join fetch b.bidder where b.auction.id = :auctionId order by b.bidPrice desc, b.id desc",
            countQuery = "select count(b) from Bid b where b.auction.id = :auctionId")
    Page<Bid> findByAuctionId(Long auctionId, Pageable pageable);

    // 현재 최고 입찰. 입찰자를 함께 읽는다.
    @Query("select b from Bid b join fetch b.bidder where b.id = :bidId")
    Optional<Bid> findWithBidder(Long bidId);

    // 회원이 이 경매에 입찰한 적이 있는지. 중단된 경매의 열람 권한을 판단한다.
    @Query("select count(b) > 0 from Bid b where b.auction.id = :auctionId and b.bidder.id = :bidderId")
    boolean existsByAuctionIdAndBidderId(Long auctionId, Long bidderId);

    // 회원이 경매마다 넣은 가장 높은 입찰의 금액. 내 입찰 목록에서 쓴다.
    @Query("select b.auction.id as auctionId, max(b.bidPrice) as myBidPrice from Bid b "
            + "where b.bidder.id = :bidderId and b.auction.id in :auctionIds group by b.auction.id")
    List<MyBidPrice> findMyBidPrices(Long bidderId, Collection<Long> auctionIds);

    // 주어진 경매 중 회원의 포인트 예약이 유지 중인 경매. 내 입찰 목록의 isReserved에 쓴다.
    @Query("select distinct b.auction.id from Bid b where b.bidder.id = :bidderId and b.reserved = true and b.auction.id in :auctionIds")
    List<Long> findReservedAuctionIds(Long bidderId, Collection<Long> auctionIds);

    // 주어진 입찰 중 회원이 넣은 입찰의 식별자. 최고 입찰자가 본인인지 판단한다.
    @Query("select b.id from Bid b where b.bidder.id = :bidderId and b.id in :bidIds")
    List<Long> findIdsByBidder(Long bidderId, Collection<Long> bidIds);

    interface MyBidPrice {
        Long getAuctionId();

        BigDecimal getMyBidPrice();
    }
}
