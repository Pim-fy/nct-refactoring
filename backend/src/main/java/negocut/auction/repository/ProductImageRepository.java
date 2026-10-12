package negocut.auction.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    // 이미지가 상품에 연결되어 있으면 경매에 쓰인 이미지다.
    @Query("select count(p) > 0 from ProductImage p where p.imageFile.id = :imageFileId")
    boolean existsByImageFileId(Long imageFileId);
}
