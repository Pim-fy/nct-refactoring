package negocut.auction.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminCategoryListResponse;
import negocut.admin.dto.AdminCategoryResponse;
import negocut.admin.dto.CategoryCreateRequest;
import negocut.admin.dto.CategoryCreateResponse;
import negocut.admin.dto.CategoryUpdateRequest;
import negocut.auction.dto.CategoryListResponse;
import negocut.auction.dto.CategoryResponse;
import negocut.auction.entity.Category;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductRepository;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;

// 카테고리 조회와 관리. (기능 명세서 3-4, 3-10)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    // 요청의 sortOrder 상한(@Max)과 같은 값이다.
    private static final int MAX_SORT_ORDER = 9999;

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    // 공개 목록: 삭제되지 않고 사용 중인 카테고리만
    public CategoryListResponse listActive() {
        return new CategoryListResponse(categoryRepository.findByDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(category -> CategoryResponse.from(category))
                .toList());
    }

    // 관리자 목록: 삭제되지 않은 카테고리 전부(사용하지 않는 카테고리 포함)와 상품 수
    public AdminCategoryListResponse listAll() {
        Map<Long, Long> counts = productRepository.countGroupedByCategory().stream()
                .collect(Collectors.toMap(count -> count.getCategoryId(), count -> count.getProductCount()));
        List<AdminCategoryResponse> categories = categoryRepository.findByDeletedFalseOrderBySortOrderAscIdAsc().stream()
                .map(category -> AdminCategoryResponse.of(category, counts.getOrDefault(category.getId(), 0L)))
                .toList();
        return new AdminCategoryListResponse(categories);
    }

    // 삭제된 카테고리와 같은 이름이면 새로 만들지 않고 그 카테고리를 되살린다. 삭제되지 않은 카테고리와 같은 이름이면 거부한다.
    @Transactional
    public CategoryCreateResponse create(CategoryCreateRequest request) {
        // 순서를 생략하면 맨 뒤 번호를 받되, 입력 규칙의 상한(9999)을 넘지 않게 한다.
        int sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : Math.min(categoryRepository.findMaxSortOrder() + 1, MAX_SORT_ORDER);

        Category existing = categoryRepository.findByName(request.name()).orElse(null);
        if (existing != null) {
            if (!existing.isDeleted()) {
                throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
            }
            existing.restore(sortOrder);
            return new CategoryCreateResponse(existing.getId());
        }

        Category saved = saveAndFlush(Category.create(request.name(), sortOrder));
        return new CategoryCreateResponse(saved.getId());
    }

    @Transactional
    public AdminCategoryResponse update(Long categoryId, CategoryUpdateRequest request) {
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Category category = findNotDeleted(categoryId);

        if (request.name() != null && !request.name().equals(category.getName())) {
            // 삭제된 카테고리가 쓰는 이름도 중복으로 본다. (이름은 삭제된 행까지 포함해 유일하다.)
            Category sameName = categoryRepository.findByName(request.name()).orElse(null);
            if (sameName != null && !sameName.getId().equals(category.getId())) {
                throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
            }
            category.rename(request.name());
        }
        if (request.sortOrder() != null) {
            category.changeSortOrder(request.sortOrder());
        }
        if (request.isActive() != null) {
            category.changeActive(request.isActive());
        }
        saveAndFlush(category);

        return AdminCategoryResponse.of(category, productRepository.countByCategoryId(category.getId()));
    }

    // 해당 카테고리를 쓰는 상품이 하나라도 있으면 삭제할 수 없다. 이때는 사용 여부만 끈다. (기능 명세서 3-10)
    // READ_COMMITTED로 카테고리 행을 먼저 잠근 뒤 상품 수를 센다. (경매 등록은 같은 행을 공유 잠금으로 읽는다.)
    // 첫 일반 조회 시점에 읽기 스냅샷이 고정되는 REPEATABLE READ에서는, 잠금을 기다린 사이에 커밋된 상품을 세지 못한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long categoryId) {
        Category category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
        if (productRepository.countByCategoryId(category.getId()) > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_IN_USE);
        }
        category.delete();
    }

    private Category findNotDeleted(Long categoryId) {
        return categoryRepository.findByIdAndDeletedFalse(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
    }

    // 이름 중복 확인과 저장 사이에 같은 이름으로 다른 요청이 먼저 저장되면 DB의 유니크 제약이 막는다.
    // 그 오류를 500이 아니라 이름 중복 오류로 바꾼다.
    private Category saveAndFlush(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATED);
        }
    }
}
