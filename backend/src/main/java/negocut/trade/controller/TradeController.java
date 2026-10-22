package negocut.trade.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import negocut.auth.security.AllowTradeRestricted;
import negocut.auth.security.AuthMember;
import negocut.common.idempotency.SkipIdempotency;
import negocut.common.response.ApiResponse;
import negocut.common.response.PageResult;
import negocut.trade.dto.CancelRequestBody;
import negocut.trade.dto.DeliveryRequest;
import negocut.trade.dto.ShipmentRequest;
import negocut.trade.dto.TradeActionResponses;
import negocut.trade.dto.TradeDetailResponse;
import negocut.trade.dto.TradeSummaryResponse;
import negocut.trade.service.TradeCancelService;
import negocut.trade.service.TradeService;

// 거래 조회와 상태 변경 (기능 명세서 3-8, API 명세서 5장)
// 관리자 계정은 쓸 수 없고(403 ACCESS_DENIED), 거래 제한 회원도 진행 중인 거래를 마무리할 수 있어야 하므로
// 쓰기 요청마다 @AllowTradeRestricted를 붙인다. (기능 명세서 2-2) 당사자가 아니면 403 TRADE_ACCESS_DENIED다.
@RestController
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;
    private final TradeCancelService tradeCancelService;

    @GetMapping("/api/members/me/trades")
    public ApiResponse<PageResult<TradeSummaryResponse>> myTrades(
            @AuthenticationPrincipal AuthMember member,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(tradeService.getMyTrades(member.memberId(), role, status, page, size));
    }

    @GetMapping("/api/trades/{tradeId}")
    public ApiResponse<TradeDetailResponse> trade(@AuthenticationPrincipal AuthMember member, @PathVariable Long tradeId) {
        return ApiResponse.success(tradeService.getTrade(member.memberId(), tradeId));
    }

    @AllowTradeRestricted
    @PutMapping("/api/trades/{tradeId}/delivery")
    public ApiResponse<TradeActionResponses.DeliverySaved> delivery(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long tradeId,
            @Valid @RequestBody DeliveryRequest request) {
        return ApiResponse.success(tradeService.saveDelivery(member.memberId(), tradeId, request));
    }

    @AllowTradeRestricted
    @PostMapping("/api/trades/{tradeId}/shipment")
    public ApiResponse<TradeActionResponses.ShipmentRegistered> shipment(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long tradeId,
            @Valid @RequestBody ShipmentRequest request) {
        return ApiResponse.success(tradeService.registerShipment(member.memberId(), tradeId, request));
    }

    // 같은 사유로 다시 요청해도 새로 판정한다. 처리 중인 요청 1건과 요청 3회 제한을 서버가 확인하므로 되풀이해도 중복되지 않고,
    // 거절된 뒤의 재요청에 이전 요청의 성공 응답이 재생되는 것을 막는다.
    @SkipIdempotency
    @AllowTradeRestricted
    @PostMapping("/api/trades/{tradeId}/cancel-requests")
    public ApiResponse<TradeActionResponses.CancelRequested> requestCancel(
            @AuthenticationPrincipal AuthMember member,
            @PathVariable Long tradeId,
            @Valid @RequestBody CancelRequestBody request) {
        return ApiResponse.success(tradeCancelService.request(member.memberId(), tradeId, request.reason()));
    }

    @AllowTradeRestricted
    @PostMapping("/api/trades/{tradeId}/cancel-requests/{requestId}/accept")
    public ApiResponse<TradeActionResponses.CancelAccepted> acceptCancel(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long tradeId, @PathVariable Long requestId) {
        return ApiResponse.success(tradeCancelService.accept(member.memberId(), tradeId, requestId));
    }

    @AllowTradeRestricted
    @PostMapping("/api/trades/{tradeId}/cancel-requests/{requestId}/reject")
    public ApiResponse<TradeActionResponses.CancelRejected> rejectCancel(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long tradeId, @PathVariable Long requestId) {
        return ApiResponse.success(tradeCancelService.reject(member.memberId(), tradeId, requestId));
    }

    @AllowTradeRestricted
    @PostMapping("/api/trades/{tradeId}/confirm")
    public ApiResponse<TradeActionResponses.PurchaseConfirmed> confirm(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long tradeId) {
        return ApiResponse.success(tradeService.confirm(member.memberId(), tradeId));
    }
}
