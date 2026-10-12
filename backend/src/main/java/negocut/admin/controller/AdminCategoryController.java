package negocut.admin.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminCategoryListResponse;
import negocut.admin.dto.AdminCategoryResponse;
import negocut.admin.dto.CategoryCreateRequest;
import negocut.admin.dto.CategoryCreateResponse;
import negocut.admin.dto.CategoryUpdateRequest;
import negocut.auction.service.CategoryService;
import negocut.common.response.ApiResponse;

// 관리자 전용(/api/admin/** 은 보안 설정에서 ADMIN만 허용한다). (기능 명세서 3-10)
@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<AdminCategoryListResponse> list() {
        return ApiResponse.success(categoryService.listAll());
    }

    @PostMapping
    public ApiResponse<CategoryCreateResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.success(categoryService.create(request));
    }

    @PatchMapping("/{categoryId}")
    public ApiResponse<AdminCategoryResponse> update(
            @PathVariable Long categoryId, @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.success(categoryService.update(categoryId, request));
    }

    @DeleteMapping("/{categoryId}")
    public ApiResponse<Void> delete(@PathVariable Long categoryId) {
        categoryService.delete(categoryId);
        return ApiResponse.success();
    }
}
