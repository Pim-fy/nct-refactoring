package negocut.auction.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    // 이미지가 상품에 연결되어 있으면 경매에 쓰인 이미지다.
    @Query("select count(p) > 0 from ProductImage p where p.imageFile.id = :imageFileId")
    boolean existsByImageFileId(Long imageFileId);

    // 이미지가 상품에 연결되어 있고 연결된 상품 중 경매가 중단된 것이 하나도 없으면 누구나 볼 수 있는 이미지다. 중단된 경매의 이미지는 공개하지 않는다.
    // (한 이미지는 한 경매에만 쓰이지만, 데이터가 어긋나 둘 이상에 연결돼도 중단된 쪽이 있으면 비공개로 둔다.)
    @Query("select count(p) > 0 from ProductImage p where p.imageFile.id = :imageFileId "
            + "and not exists (select pi.id from ProductImage pi, Auction a where pi.imageFile.id = :imageFileId and a.product = pi.product "
            + "and a.auctionStatus = negocut.auction.entity.AuctionStatus.STOPPED)")
    boolean existsPublicByImageFileId(Long imageFileId);

    // 이미지가 중단된 경매의 상품 이미지이고, 보는 사람이 그 경매의 판매자이거나 입찰한 적이 있는 회원인지. 중단된 경매의 상세를 볼 수 있는 사람과 같다.
    @Query("select count(p) > 0 from ProductImage p, Auction a where p.imageFile.id = :imageFileId and a.product = p.product "
            + "and a.auctionStatus = negocut.auction.entity.AuctionStatus.STOPPED "
            + "and (a.seller.id = :memberId or exists (select b.id from Bid b where b.auction = a and b.bidder.id = :memberId))")
    boolean existsStoppedAuctionParticipant(Long imageFileId, Long memberId);

    // 주어진 이미지 중 이미 상품에 연결된(다른 경매에 쓰인) 이미지의 식별자
    @Query("select p.imageFile.id from ProductImage p where p.imageFile.id in :imageFileIds")
    List<Long> findUsedImageFileIds(Collection<Long> imageFileIds);

    // 상품의 이미지를 순서대로. 경매 상세에서 쓴다.
    @Query("select p from ProductImage p join fetch p.imageFile where p.product.id = :productId order by p.sortOrder")
    List<ProductImage> findByProductId(Long productId);

    // 상품들의 대표 이미지(순서가 1인 이미지). 목록의 썸네일에 쓴다.
    @Query("select p from ProductImage p join fetch p.imageFile where p.product.id in :productIds and p.sortOrder = 1")
    List<ProductImage> findThumbnails(Collection<Long> productIds);
}
