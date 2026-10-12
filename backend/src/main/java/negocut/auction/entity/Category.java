package negocut.auction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import negocut.common.entity.BaseTimeEntity;

// 상품 분류. 삭제는 소프트 삭제(is_deleted)이며, 삭제해도 기록은 남기고 목록과 등록 화면에서만 제외한다. (기능 명세서 3-10)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "category",
        uniqueConstraints = @UniqueConstraint(name = "uk_category_name", columnNames = "name"))
public class Category extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    public static Category create(String name, int sortOrder) {
        Category category = new Category();
        category.name = name;
        category.sortOrder = sortOrder;
        category.active = true;
        category.deleted = false;
        return category;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public void delete() {
        this.deleted = true;
    }

    // 삭제된 카테고리와 같은 이름으로 다시 추가되면 새 행을 만들지 않고 이 행을 되살린다.
    public void restore(int sortOrder) {
        this.deleted = false;
        this.active = true;
        this.sortOrder = sortOrder;
    }
}
