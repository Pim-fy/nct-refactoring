package negocut.auction.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import negocut.auction.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

    interface CategoryProductCount {
        Long getCategoryId();

        long getProductCount();
    }

    // 카테고리를 쓰는 상품이 있는지는 삭제 가능 여부 판단에 쓴다. (기능 명세서 3-10)
    @Query("select count(p) from Product p where p.category.id = :categoryId")
    long countByCategoryId(Long categoryId);

    @Query("select p.category.id as categoryId, count(p) as productCount from Product p group by p.category.id")
    List<CategoryProductCount> countGroupedByCategory();
}
