package negocut.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminAuctionResponses;
import negocut.admin.dto.AuctionStopRequest;
import negocut.admin.service.AdminAuctionService;
import negocut.auction.entity.AuctionStatus;
import negocut.auth.security.AuthMember;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;

// 관리자 경매 관리 (기능 명세서 3-10, API 명세서 7장). /api/admin/** 은 보안 설정에서 ADMIN만 허용한다.
// 중단은 같은 요청을 다시 보내도 서버가 현재 상태로 새로 판정하므로(이미 중단된 경매는 409) 중복 요청 방지에서 뺀다.
@RestController
@RequestMapping("/api/admin/auctions")
@RequiredArgsConstructor
public class AdminAuctionController {

    private final AdminAuctionService adminAuctionService;

    @GetMapping
    public ApiResponse<PageResult<AdminAuctionResponses.Item>> list(
            @RequestParam(required = false) AuctionStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminAuctionService.list(status, keyword, page, size));
    }

    @SkipIdempotency
    @PostMapping("/{auctionId}/stop")
    public ApiResponse<AdminAuctionResponses.Stopped> stop(
            @AuthenticationPrincipal AuthMember admin, @PathVariable Long auctionId, @Valid @RequestBody AuctionStopRequest request) {
        return ApiResponse.success(adminAuctionService.stop(admin.memberId(), auctionId, request));
    }
}
