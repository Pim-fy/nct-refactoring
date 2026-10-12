package negocut.auction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;
import negocut.file.entity.ImageFile;

// 상품에 연결된 이미지. sort_order가 가장 작은 첫 장이 대표 이미지이고 최대 10장이다. (DB 설계서 2-4)
// 이 행이 있으면 그 이미지는 경매에 쓰인 것으로 보아 공개하고 삭제하지 못하게 한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "product_image",
        indexes = @Index(name = "ix_product_image_product", columnList = "product_id, sort_order"))
public class ProductImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_file_id", nullable = false)
    private ImageFile imageFile;

    @Column(nullable = false)
    private int sortOrder;

    public static ProductImage create(Product product, ImageFile imageFile, int sortOrder) {
        ProductImage productImage = new ProductImage();
        productImage.product = product;
        productImage.imageFile = imageFile;
        productImage.sortOrder = sortOrder;
        return productImage;
    }
}
