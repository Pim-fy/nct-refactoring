package negocut.auction.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auction.dto.AuctionRegisterRequest;
import negocut.auction.dto.AuctionRegisterResponse;
import negocut.auction.dto.AuctionSummaryResponse;
import negocut.auction.service.AuctionService;
import negocut.auth.security.AuthMember;
import negocut.common.exception.ErrorCode;
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;

// 경매 등록과 내 경매 목록 (기능 명세서 3-4, API 명세서 3장)
// 등록은 쓰기 요청이라 거래 제한 회원은 기본 규칙으로 403 MEMBER_TRADE_RESTRICTED이고, 관리자 계정은 403 ACCESS_DENIED다.
@RestController
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;

    // BindingResult를 받으면 형식 오류가 경매 전용 코드(AUCTION_INPUT_INVALID)와 필드별 사유(details)로 응답된다.
    @PostMapping("/api/auctions")
    public ApiResponse<AuctionRegisterResponse> register(
            @AuthenticationPrincipal AuthMember member,
            @Valid @RequestBody AuctionRegisterRequest request,
            BindingResult bindingResult) {
        InputInvalidException.throwIfInvalid(bindingResult, ErrorCode.AUCTION_INPUT_INVALID);
        return ApiResponse.success(auctionService.register(member, request));
    }

    @GetMapping("/api/members/me/auctions")
    public ApiResponse<PageResult<AuctionSummaryResponse>> myAuctions(
            @AuthenticationPrincipal AuthMember member,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(auctionService.getMyAuctions(member.memberId(), page, size));
    }
}
