package negocut.auction.dto;

import negocut.auction.entity.Category;

// API 명세서 3장 GET /api/categories
public record CategoryResponse(Long categoryId, String name, int sortOrder) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSortOrder());
    }
}
