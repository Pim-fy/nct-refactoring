package negocut.auction.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.Auction;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 경매 상세에 필요한 상품·카테고리·판매자를 함께 읽는다.
    @Query("select a from Auction a join fetch a.product p join fetch p.category join fetch a.seller where a.id = :id")
    Optional<Auction> findDetailById(Long id);

    // 내가 등록한 경매. 목록에 필요한 상품과 카테고리를 함께 읽는다. 최신 등록순이다.
    @Query(value = "select a from Auction a join fetch a.product p join fetch p.category "
            + "where a.seller.id = :sellerId order by a.createdAt desc, a.id desc",
            countQuery = "select count(a) from Auction a where a.seller.id = :sellerId")
    Page<Auction> findBySellerId(Long sellerId, Pageable pageable);
}
