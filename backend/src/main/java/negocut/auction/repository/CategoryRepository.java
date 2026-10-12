package negocut.auction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import negocut.auction.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    // 새 경매 등록과 공개 목록에서 쓰는 카테고리: 삭제되지 않고 사용 중인 것
    List<Category> findByDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc();

    // 관리자 목록: 삭제되지 않은 것 전부(사용하지 않는 카테고리 포함)
    List<Category> findByDeletedFalseOrderBySortOrderAscIdAsc();

    Optional<Category> findByIdAndDeletedFalse(Long id);

    // 경매 등록이 카테고리를 쓰는 동안 삭제되지 않게 공유 잠금으로 읽는다.
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select c from Category c where c.id = :id and c.deleted = false")
    Optional<Category> findByIdForShare(Long id);

    // 삭제는 배타 잠금으로 읽어, 진행 중인 등록이 끝난 뒤 상품 수를 센다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Category c where c.id = :id and c.deleted = false")
    Optional<Category> findByIdForUpdate(Long id);

    // 이름은 삭제된 카테고리까지 포함해 유일하다. (DB 설계서의 name UNIQUE)
    Optional<Category> findByName(String name);

    @Query("select coalesce(max(c.sortOrder), 0) from Category c where c.deleted = false")
    int findMaxSortOrder();
}
