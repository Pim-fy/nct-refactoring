package negocut.trade.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.member.entity.Member;
import negocut.member.repository.MemberRepository;
import negocut.point.dto.PointResponse;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.service.PointService;
import negocut.trade.dto.TradeActionResponses;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeCancelRequest;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.TradeCancelRequestRepository;
import negocut.trade.repository.TradeRepository;

// 거래 취소 요청·수락·거절. (기능 명세서 3-8 거래 취소)
// 한쪽이 요청하고 상대방이 수락하면 거래가 취소되고 낙찰자의 예약 포인트가 반환된다. 준비·진행 상태에서만 할 수 있다.
// 모든 처리는 거래 행을 먼저 잠그고 상태를 다시 확인한다. 같은 거래의 구매 확정·취소 요청·수락이 겹쳐도 먼저 처리된 쪽만 성립한다.
// 잠금 순서는 거래 행 → 낙찰자 잔액 행이다. 회원 행은 먼저 잠그지 않는다. (기능 명세서 D-3)
@Service
@RequiredArgsConstructor
public class TradeCancelService {

    // 한 거래에서 당사자 한 명이 낼 수 있는 취소 요청 수. 거절·종결된 요청도 센다.
    static final int MAX_REQUESTS_PER_PARTY = 3;

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TradeRepository tradeRepository;
    private final TradeCancelRequestRepository cancelRequestRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final MemberRepository memberRepository;
    private final PointService pointService;

    // 취소를 요청한다. 당사자만, 준비·진행 상태에서만, 처리 중인 요청이 없을 때만 할 수 있다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.CancelRequested request(Long memberId, Long tradeId, String reason) {
        Trade trade = lockTrade(tradeId);
        requireParty(trade, memberId);
        requireCancelable(trade);
        if (cancelRequestRepository.findProcessingByTradeId(tradeId).isPresent()) {
            throw new BusinessException(ErrorCode.TRADE_CANCEL_ALREADY_REQUESTED);
        }
        if (cancelRequestRepository.countByTradeIdAndRequesterId(tradeId, memberId) >= MAX_REQUESTS_PER_PARTY) {
            throw new BusinessException(ErrorCode.TRADE_CANCEL_LIMIT_EXCEEDED);
        }

        Member requester = memberRepository.getReferenceById(memberId);
        TradeCancelRequest request = cancelRequestRepository.save(TradeCancelRequest.open(trade, requester, reason));
        return new TradeActionResponses.CancelRequested(request.getId(), request.getRequestStatus());
    }

    // 상대방이 취소 요청을 수락한다. 거래가 취소되고 낙찰자의 예약 포인트가 반환된다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.CancelAccepted accept(Long memberId, Long tradeId, Long requestId) {
        Trade trade = lockTrade(tradeId);
        requireParty(trade, memberId);
        TradeCancelRequest request = requireRespondable(trade, memberId, requestId);

        LocalDateTime now = LocalDateTime.now(ZONE);
        Long buyerId = trade.getBuyer().getId();
        PointBalance buyerBalance = pointBalanceRepository.findByMemberIdForUpdate(buyerId)
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + buyerId));
        pointService.refund(buyerBalance, trade.getBuyer(), trade.getTradePrice(), trade.getId());
        trade.cancel(now);
        request.accept(now);

        // 반환을 받는 쪽(낙찰자)에게만 갱신된 포인트를 담는다.
        PointResponse point = memberId.equals(buyerId) ? PointResponse.from(buyerBalance) : null;
        return new TradeActionResponses.CancelAccepted(tradeId, trade.getTradeStatus(), request.getRequestStatus(), point);
    }

    // 상대방이 취소 요청을 거절한다. 요청은 종료되고 거래는 원래 상태로 남는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.CancelRejected reject(Long memberId, Long tradeId, Long requestId) {
        Trade trade = lockTrade(tradeId);
        requireParty(trade, memberId);
        TradeCancelRequest request = requireRespondable(trade, memberId, requestId);

        request.reject(LocalDateTime.now(ZONE));
        return new TradeActionResponses.CancelRejected(tradeId, trade.getTradeStatus(), request.getRequestStatus());
    }

    // 수락·거절할 수 있는 요청을 돌려준다. 요청이 이 거래의 것이고 처리 중이어야 하며, 거래가 준비·진행이어야 하고, 응답하는 사람은 요청자가 아니어야 한다.
    private TradeCancelRequest requireRespondable(Trade trade, Long memberId, Long requestId) {
        TradeCancelRequest request = cancelRequestRepository.findByIdAndTradeId(requestId, trade.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRADE_CANCEL_REQUEST_NOT_FOUND));
        if (!request.isProcessing()) {
            throw new BusinessException(ErrorCode.TRADE_CANCEL_REQUEST_CLOSED);
        }
        requireCancelable(trade);
        if (request.getRequester().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);   // 자기 요청에는 응답할 수 없다.
        }
        return request;
    }

    private void requireCancelable(Trade trade) {
        if (trade.getTradeStatus() != TradeStatus.READY && trade.getTradeStatus() != TradeStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TRADE_STATUS_INVALID);
        }
    }

    private void requireParty(Trade trade, Long memberId) {
        if (!trade.getSeller().getId().equals(memberId) && !trade.getBuyer().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);
        }
    }

    private Trade lockTrade(Long tradeId) {
        return tradeRepository.findByIdForUpdate(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
    }
}
