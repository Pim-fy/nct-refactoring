package negocut.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminDisputeResponses;
import negocut.admin.dto.DisputeJudgeRequest;
import negocut.admin.service.AdminDisputeService;
import negocut.auth.security.AuthMember;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;
import negocut.trade.entity.DisputeStatus;

// 관리자 분쟁 관리 (기능 명세서 3-8·3-10, API 명세서 7장). /api/admin/** 은 보안 설정에서 ADMIN만 허용한다.
// 판정은 같은 요청을 다시 보내도 서버가 현재 상태로 새로 판정하므로(판정이 끝난 분쟁은 409) 중복 요청 방지에서 뺀다.
@RestController
@RequiredArgsConstructor
public class AdminDisputeController {

    private final AdminDisputeService adminDisputeService;

    @GetMapping("/api/admin/disputes")
    public ApiResponse<PageResult<AdminDisputeResponses.Item>> list(
            @RequestParam(required = false) DisputeStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminDisputeService.list(status, page, size));
    }

    @GetMapping("/api/admin/trades/{tradeId}")
    public ApiResponse<AdminDisputeResponses.TradeDetail> trade(@PathVariable Long tradeId) {
        return ApiResponse.success(adminDisputeService.getTrade(tradeId));
    }

    @SkipIdempotency
    @PostMapping("/api/admin/disputes/{disputeId}/judge")
    public ApiResponse<AdminDisputeResponses.Judged> judge(
            @AuthenticationPrincipal AuthMember admin, @PathVariable Long disputeId, @Valid @RequestBody DisputeJudgeRequest request) {
        return ApiResponse.success(adminDisputeService.judge(admin.memberId(), disputeId, request));
    }
}
