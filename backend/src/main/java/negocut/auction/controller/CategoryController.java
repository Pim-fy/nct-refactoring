package negocut.auction.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import negocut.auction.dto.CategoryListResponse;
import negocut.auction.service.CategoryService;
import negocut.common.response.ApiResponse;

@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    // 공개: 사용 중인 카테고리 목록
    @GetMapping
    public ApiResponse<CategoryListResponse> list() {
        return ApiResponse.success(categoryService.listActive());
    }
}
