package negocut.admin.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminMemberResponses;
import negocut.admin.dto.MemberReleaseRequest;
import negocut.admin.dto.MemberRestrictRequest;
import negocut.admin.service.AdminMemberService;
import negocut.auth.security.AuthMember;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;
import negocut.member.entity.MemberStatus;

// 관리자 회원 관리 (기능 명세서 3-10, API 명세서 7장). /api/admin/** 은 보안 설정에서 ADMIN만 허용한다.
// 제한·해제는 같은 요청을 다시 보내도 서버가 현재 상태로 새로 판정하므로(이미 제한·해제된 회원은 409) 중복 요청 방지에서 뺀다.
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping
    public ApiResponse<PageResult<AdminMemberResponses.Item>> list(
            @RequestParam(required = false) MemberStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminMemberService.list(status, keyword, page, size));
    }

    @SkipIdempotency
    @PostMapping("/{memberId}/restrictions")
    public ApiResponse<AdminMemberResponses.Restricted> restrict(
            @AuthenticationPrincipal AuthMember admin, @PathVariable Long memberId, @Valid @RequestBody MemberRestrictRequest request) {
        return ApiResponse.success(adminMemberService.restrict(admin.memberId(), memberId, request));
    }

    @SkipIdempotency
    @DeleteMapping("/{memberId}/restrictions")
    public ApiResponse<AdminMemberResponses.Released> release(
            @AuthenticationPrincipal AuthMember admin, @PathVariable Long memberId, @Valid @RequestBody MemberReleaseRequest request) {
        return ApiResponse.success(adminMemberService.release(admin.memberId(), memberId, request));
    }
}
