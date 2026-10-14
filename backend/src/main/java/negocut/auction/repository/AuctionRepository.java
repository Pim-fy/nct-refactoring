package negocut.auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import negocut.auction.entity.Auction;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 입찰 처리에서 경매 행을 잠그고 읽는다. 같은 경매에 대한 입찰은 이 잠금으로 한 건씩 처리된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auction a where a.id = :id")
    Optional<Auction> findByIdForUpdate(Long id);

    // 종료 시각이 지났는데 아직 진행 중인 경매의 식별자. 종료 시각이 이른 것부터 한 번에 일정 개수만 읽는다.
    @Query("select a.id from Auction a where a.auctionStatus = negocut.auction.entity.AuctionStatus.IN_PROGRESS and a.endAt <= :now order by a.endAt, a.id")
    List<Long> findIdsToClose(LocalDateTime now, Pageable pageable);

    // 내가 입찰한 경매. 내가 마지막으로 입찰한 순서(최신 먼저)다. 목록에 필요한 상품과 카테고리를 함께 읽는다.
    @Query(value = "select a from Auction a join fetch a.product p join fetch p.category "
            + "where a.id in (select b.auction.id from Bid b where b.bidder.id = :bidderId) "
            + "order by (select max(b2.bidAt) from Bid b2 where b2.auction.id = a.id and b2.bidder.id = :bidderId) desc, a.id desc",
            countQuery = "select count(a) from Auction a where a.id in (select b.auction.id from Bid b where b.bidder.id = :bidderId)")
    Page<Auction> findBidOnBy(Long bidderId, Pageable pageable);

    // 경매 상세에 필요한 상품·카테고리·판매자를 함께 읽는다.
    @Query("select a from Auction a join fetch a.product p join fetch p.category join fetch a.seller where a.id = :id")
    Optional<Auction> findDetailById(Long id);

    // 내가 등록한 경매. 목록에 필요한 상품과 카테고리를 함께 읽는다. 최신 등록순이다.
    @Query(value = "select a from Auction a join fetch a.product p join fetch p.category "
            + "where a.seller.id = :sellerId order by a.createdAt desc, a.id desc",
            countQuery = "select count(a) from Auction a where a.seller.id = :sellerId")
    Page<Auction> findBySellerId(Long sellerId, Pageable pageable);
}
