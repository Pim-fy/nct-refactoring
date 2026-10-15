package negocut.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import negocut.auction.repository.ProductImageRepository;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.trade.dto.DeliveryRequest;
import negocut.trade.dto.ShipmentRequest;
import negocut.trade.dto.TradeActionResponses;
import negocut.trade.dto.TradeDetailResponse;
import negocut.trade.dto.TradeSummaryResponse;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeAction;
import negocut.trade.entity.TradeDelivery;
import negocut.trade.entity.TradeRole;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeDeliveryRepository;
import negocut.trade.repository.TradeRepository;
import negocut.trade.repository.TradeSearchRepository;

// 거래 조회와 배송지 입력, 발송 등록, 구매 확정. (기능 명세서 3-8)
// 거래를 당사자(판매자·낙찰자) 밖에서 열거나 바꿀 수 없다. 상태를 바꾸는 처리는 거래 행을 먼저 잠그고 상태를 다시 확인한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;

    private final TradeRepository tradeRepository;
    private final TradeDeliveryRepository deliveryRepository;
    private final TradeSearchRepository tradeSearchRepository;
    private final ProductImageRepository productImageRepository;
    private final TradeConfirmProcessor confirmProcessor;

    // 내 거래 목록. role(SELLER·BUYER)과 status는 생략할 수 있다. 최근 거래가 먼저다.
    @Transactional(readOnly = true)
    public PageResult<TradeSummaryResponse> getMyTrades(Long memberId, String role, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        TradeRole roleFilter = parse(TradeRole.class, role);
        TradeStatus statusFilter = parse(TradeStatus.class, status);

        Page<Trade> trades = tradeSearchRepository.search(memberId, roleFilter, statusFilter, PageRequest.of(page, size));
        List<Long> productIds = trades.getContent().stream()
                .map(trade -> trade.getAuctionResult().getAuction().getProduct().getId()).toList();
        Map<Long, Long> thumbnails = thumbnails(productIds);
        return PageResult.from(trades.map(trade -> TradeSummaryResponse.of(trade, roleOf(trade, memberId),
                thumbnails.get(trade.getAuctionResult().getAuction().getProduct().getId()))));
    }

    @Transactional(readOnly = true)
    public TradeDetailResponse getTrade(Long memberId, Long tradeId) {
        Trade trade = tradeRepository.findDetailById(tradeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        TradeRole role = requireParty(trade, memberId);
        TradeDelivery delivery = deliveryRepository.findByTradeId(tradeId).orElse(null);
        Long productId = trade.getAuctionResult().getAuction().getProduct().getId();
        return TradeDetailResponse.of(trade, role, delivery, thumbnails(List.of(productId)).get(productId),
                availableActions(trade, role, delivery != null));
    }

    // 낙찰자가 배송지를 입력한다. 준비 상태에서만 할 수 있고, 발송이 등록되기 전까지는 다시 입력해 고칠 수 있다. (API-3)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.DeliverySaved saveDelivery(Long memberId, Long tradeId, DeliveryRequest request) {
        Trade trade = lockTrade(tradeId);
        TradeRole role = requireParty(trade, memberId);
        if (role != TradeRole.BUYER) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);   // 배송지는 낙찰자만 입력할 수 있다.
        }
        requireStatus(trade, TradeStatus.READY);

        TradeDelivery delivery = deliveryRepository.findByTradeId(tradeId).orElse(null);
        if (delivery == null) {
            delivery = deliveryRepository.save(TradeDelivery.create(trade, request.receiverName(), request.receiverPhone(),
                    request.zipCode(), request.address(), blankToNull(request.addressDetail()), blankToNull(request.deliveryMessage())));
        } else {
            delivery.change(request.receiverName(), request.receiverPhone(), request.zipCode(),
                    request.address(), blankToNull(request.addressDetail()), blankToNull(request.deliveryMessage()));
        }
        return new TradeActionResponses.DeliverySaved(tradeId, trade.getTradeStatus(), availableActions(trade, role, true));
    }

    // 판매자가 발송을 등록한다. 준비 상태이고 배송지가 입력되어 있어야 하며, 등록하면 진행 상태가 되고 구매 확정 기한(발송 후 10일)이 정해진다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.ShipmentRegistered registerShipment(Long memberId, Long tradeId, ShipmentRequest request) {
        Trade trade = lockTrade(tradeId);
        TradeRole role = requireParty(trade, memberId);
        if (role != TradeRole.SELLER) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);   // 발송은 판매자만 등록할 수 있다.
        }
        requireStatus(trade, TradeStatus.READY);
        TradeDelivery delivery = deliveryRepository.findByTradeId(tradeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_STATUS_INVALID));   // 배송지 입력 전에는 발송할 수 없다.

        LocalDateTime now = LocalDateTime.now(ZONE);
        delivery.ship(request.courierName(), request.trackingNumber(), now);
        trade.startShipping(now);
        return new TradeActionResponses.ShipmentRegistered(tradeId, trade.getTradeStatus(),
                TradeSummaryResponse.toOffset(now), TradeSummaryResponse.toOffset(trade.getConfirmDueAt()),
                availableActions(trade, role, true));
    }

    // 낙찰자가 구매를 확정한다. 구매 확정을 먼저 저장하고(커밋), 이어서 포인트 지급과 완료 전환을 하나의 트랜잭션으로 처리한다.
    // 지급이 실패해도 구매 확정은 남아 거래가 구매 확정 상태에 멈추고, 그 상태로 응답한다. 중복 지급은 없다. 이 메서드는 트랜잭션 없이 실행한다.
    public TradeActionResponses.PurchaseConfirmed confirm(Long memberId, Long tradeId) {
        confirmProcessor.confirmByBuyer(tradeId, memberId);
        try {
            confirmProcessor.pay(tradeId);
        } catch (RuntimeException e) {
            log.error("[Trade] 포인트 지급에 실패해 거래가 구매 확정 상태에 멈췄습니다. tradeId={}", tradeId, e);
        }
        Trade trade = tradeRepository.findById(tradeId).orElseThrow();
        return new TradeActionResponses.PurchaseConfirmed(tradeId, trade.getTradeStatus(),
                TradeSummaryResponse.toOffset(trade.getConfirmedAt()), TradeSummaryResponse.toOffset(trade.getCompletedAt()), List.of());
    }

    // 보는 사람이 지금 할 수 있는 행동. 역할, 거래 상태, 배송지 입력 여부로 정한다. (API 명세서 5-1)
    // 서버는 실제 요청이 들어올 때 같은 조건을 다시 검증한다.
    static List<TradeAction> availableActions(Trade trade, TradeRole role, boolean hasDelivery) {
        List<TradeAction> actions = new ArrayList<>();
        if (role == TradeRole.BUYER && trade.getTradeStatus() == TradeStatus.READY) {
            actions.add(TradeAction.INPUT_DELIVERY);
        }
        if (role == TradeRole.SELLER && trade.getTradeStatus() == TradeStatus.READY && hasDelivery) {
            actions.add(TradeAction.REGISTER_SHIPMENT);
        }
        if (role == TradeRole.BUYER && trade.getTradeStatus() == TradeStatus.IN_PROGRESS) {
            actions.add(TradeAction.CONFIRM_PURCHASE);
        }
        return actions;
    }

    private Trade lockTrade(Long tradeId) {
        return tradeRepository.findByIdForUpdate(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
    }

    // 거래 당사자인지 확인하고 역할을 돌려준다. 당사자가 아니면 403이다.
    private TradeRole requireParty(Trade trade, Long memberId) {
        TradeRole role = roleOf(trade, memberId);
        if (role == null) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);
        }
        return role;
    }

    private TradeRole roleOf(Trade trade, Long memberId) {
        if (trade.getSeller().getId().equals(memberId)) {
            return TradeRole.SELLER;
        }
        if (trade.getBuyer().getId().equals(memberId)) {
            return TradeRole.BUYER;
        }
        return null;
    }

    private void requireStatus(Trade trade, TradeStatus expected) {
        if (trade.getTradeStatus() != expected) {
            throw new BusinessException(ErrorCode.TRADE_STATUS_INVALID);
        }
    }

    private Map<Long, Long> thumbnails(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productImageRepository.findThumbnails(productIds).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(), image -> image.getImageFile().getId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // 없으면 null, 모르는 값이면 400이다.
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
