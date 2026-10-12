package negocut.file.controller;

import java.util.concurrent.TimeUnit;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import negocut.auth.security.AllowTradeRestricted;
import negocut.auth.security.AuthMember;
import negocut.common.response.ApiResponse;
import negocut.file.dto.ImageUploadResponse;
import negocut.file.entity.ImageUsage;
import negocut.file.service.ImageService;

// 이미지 업로드·조회·삭제 (기능 명세서 3-3, API 명세서 2장)
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    // 거래 제한 회원도 프로필 이미지는 올릴 수 있으므로 허용 표시를 붙이고, 상품 이미지만 서비스에서 막는다.
    // 멀티파트 요청은 중복 요청 방지에서 제외된다.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AllowTradeRestricted
    public ApiResponse<ImageUploadResponse> upload(
            @AuthenticationPrincipal AuthMember member,
            @RequestPart("file") MultipartFile file,
            @RequestParam ImageUsage usageType) {
        return ApiResponse.success(imageService.upload(member, file, usageType));
    }

    // 공개 경로다. 비로그인이면 viewer가 null이다. 응답은 봉투 없이 이미지 바이너리다.
    @GetMapping("/{imageId}")
    public ResponseEntity<Resource> get(@PathVariable Long imageId, @AuthenticationPrincipal AuthMember viewer) {
        ImageService.ImageContent content = imageService.get(imageId, viewer);

        // 경매에 쓰인 상품 이미지는 바뀌거나 지워지지 않으므로 하루 동안 캐시해도 된다.
        // 프로필 이미지는 교체·삭제될 수 있어 짧게(10분) 둔다. 올린 본인에게만 보이는 이미지는 공유 캐시에 남기지 않는다.
        CacheControl cacheControl;
        if (!content.isPublic()) {
            cacheControl = CacheControl.noStore().cachePrivate();
        } else if (content.usageType() == ImageUsage.PROFILE) {
            cacheControl = CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic();
        } else {
            cacheControl = CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(cacheControl)
                .body(content.resource());
    }

    // 거래 제한 회원도 본인 정보 관리(프로필 이미지)와 쓰지 않은 이미지 정리는 할 수 있다.
    @DeleteMapping("/{imageId}")
    @AllowTradeRestricted
    public ApiResponse<Void> delete(@PathVariable Long imageId, @AuthenticationPrincipal AuthMember member) {
        imageService.delete(imageId, member);
        return ApiResponse.success();
    }
}
