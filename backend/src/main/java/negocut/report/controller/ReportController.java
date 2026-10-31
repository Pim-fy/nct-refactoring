package negocut.report.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auth.security.AllowTradeRestricted;
import negocut.auth.security.AuthMember;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;
import negocut.report.dto.ReportRequest;
import negocut.report.dto.ReportResponses;
import negocut.report.service.ReportService;

// 신고 접수와 내 신고 내역 (기능 명세서 3-9, API 명세서 6장)
// 관리자 계정은 쓸 수 없다(403 ACCESS_DENIED). 신고는 운영 알림이므로 거래 제한 회원도 접수할 수 있다.
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @AllowTradeRestricted
    @PostMapping("/api/reports")
    public ApiResponse<ReportResponses.Received> receive(@AuthenticationPrincipal AuthMember member, @Valid @RequestBody ReportRequest request) {
        return ApiResponse.success(reportService.receive(member.memberId(), request));
    }

    @GetMapping("/api/members/me/reports")
    public ApiResponse<PageResult<ReportResponses.Mine>> myReports(
            @AuthenticationPrincipal AuthMember member,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(reportService.getMyReports(member.memberId(), page, size));
    }
}
