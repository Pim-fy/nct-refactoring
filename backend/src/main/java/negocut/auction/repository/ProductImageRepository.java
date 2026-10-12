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
