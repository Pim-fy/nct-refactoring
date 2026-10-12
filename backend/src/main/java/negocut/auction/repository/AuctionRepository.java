package negocut.auction.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.Auction;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 내가 등록한 경매. 목록에 필요한 상품과 카테고리를 함께 읽는다. 최신 등록순이다.
    @Query(value = "select a from Auction a join fetch a.product p join fetch p.category "
            + "where a.seller.id = :sellerId order by a.createdAt desc, a.id desc",
            countQuery = "select count(a) from Auction a where a.seller.id = :sellerId")
    Page<Auction> findBySellerId(Long sellerId, Pageable pageable);
}
