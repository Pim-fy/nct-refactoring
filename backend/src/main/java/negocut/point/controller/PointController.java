package negocut.point.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auth.security.AuthMember;
import negocut.common.exception.ErrorCode;
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ApiResponse;
import negocut.point.dto.PointChargeRequest;
import negocut.point.dto.PointResponse;
import negocut.point.service.PointService;

// 내 포인트 조회와 충전 (기능 명세서 3-5, API 명세서 4장)
// 충전은 쓰기 요청이라 거래 제한 회원은 기본 규칙으로 403 MEMBER_TRADE_RESTRICTED이고, 관리자 계정은 403 ACCESS_DENIED다.
@RestController
@RequiredArgsConstructor
public class PointController {

    private final PointService pointService;

    @GetMapping("/api/members/me/points")
    public ApiResponse<PointResponse> myPoints(@AuthenticationPrincipal AuthMember member) {
        return ApiResponse.success(pointService.getMyPoints(member.memberId()));
    }

    @PostMapping("/api/members/me/points/charge")
    public ApiResponse<PointResponse> charge(
            @AuthenticationPrincipal AuthMember member,
            @Valid @RequestBody PointChargeRequest request,
            BindingResult bindingResult) {
        InputInvalidException.throwIfInvalid(bindingResult, ErrorCode.POINT_CHARGE_AMOUNT_INVALID);
        return ApiResponse.success(pointService.charge(member.memberId(), request.amount()));
    }
}
