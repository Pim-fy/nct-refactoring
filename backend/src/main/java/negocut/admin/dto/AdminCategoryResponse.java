package negocut.admin.dto;

import negocut.auction.entity.Category;

// API 명세서 7장 카테고리 관리 응답. productCount는 삭제 가능 여부를 판단하는 값이다.
public record AdminCategoryResponse(Long categoryId, String name, int sortOrder, boolean isActive, long productCount) {

    public static AdminCategoryResponse of(Category category, long productCount) {
        return new AdminCategoryResponse(category.getId(), category.getName(), category.getSortOrder(),
                category.isActive(), productCount);
    }
}
