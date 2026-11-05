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
import negocut.admin.dto.AdminReportResponses;
import negocut.admin.dto.ReportProcessRequest;
import negocut.admin.service.AdminReportService;
import negocut.auth.security.AuthMember;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;
import negocut.report.entity.ReportStatus;

// 관리자 신고 관리 (기능 명세서 3-10, API 명세서 7장). /api/admin/** 은 보안 설정에서 ADMIN만 허용한다.
// 처리는 같은 요청을 다시 보내도 서버가 신고의 현재 상태로 새로 판정하므로(이미 처리된 신고는 409) 중복 요청 방지에서 뺀다.
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;

    @GetMapping
    public ApiResponse<PageResult<AdminReportResponses.Item>> list(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminReportService.list(status, page, size));
    }

    @SkipIdempotency
    @PostMapping("/{reportId}/process")
    public ApiResponse<AdminReportResponses.Processed> process(
            @AuthenticationPrincipal AuthMember admin, @PathVariable Long reportId, @Valid @RequestBody ReportProcessRequest request) {
        return ApiResponse.success(adminReportService.process(admin.memberId(), reportId, request));
    }
}
