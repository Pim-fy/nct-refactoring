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

// 경매에 올리는 상품. 판매자는 상품이 아니라 경매(auction.seller_id)에만 둔다. (DB 설계서 2-4)
// 이미지 연결과 경매는 경매 등록 구현에서 추가한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "product",
        indexes = {
                @Index(name = "ix_product_category", columnList = "category_id"),
                @Index(name = "ix_product_name", columnList = "name")
        })
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 500)
    private String conditionNote;

    public static Product create(Category category, String name, String description, String conditionNote) {
        Product product = new Product();
        product.category = category;
        product.name = name;
        product.description = description;
        product.conditionNote = conditionNote;
        return product;
    }
}
