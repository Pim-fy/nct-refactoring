package negocut.auction.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auction.dto.BidHistoryResponse;
import negocut.auction.dto.BidRequest;
import negocut.auction.dto.BidResponse;
import negocut.auction.dto.MyBidResponse;
import negocut.auction.service.BidService;
import negocut.auth.security.AuthMember;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;

// 입찰, 입찰 내역, 내 입찰 목록 (기능 명세서 3-6, API 명세서 3·4장)
// 입찰은 쓰기 요청이라 거래 제한 회원은 기본 규칙으로 403 MEMBER_TRADE_RESTRICTED이고, 관리자 계정은 403 ACCESS_DENIED다.
@RestController
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    @PostMapping("/api/auctions/{auctionId}/bids")
    public ApiResponse<BidResponse> placeBid(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long auctionId,
            @Valid @RequestBody BidRequest request) {
        return ApiResponse.success(bidService.placeBid(member, auctionId, request.bidPrice()));
    }

    // 공개 입찰 내역. 로그인했으면 본인의 입찰을 표시한다.
    @GetMapping("/api/auctions/{auctionId}/bids")
    public ApiResponse<PageResult<BidHistoryResponse>> bids(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long auctionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(bidService.getBids(auctionId, member, page, size));
    }

    @GetMapping("/api/members/me/bids")
    public ApiResponse<PageResult<MyBidResponse>> myBids(
            @AuthenticationPrincipal AuthMember member,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(bidService.getMyBids(member.memberId(), page, size));
    }
}
