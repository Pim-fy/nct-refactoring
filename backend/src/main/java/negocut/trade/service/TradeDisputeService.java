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
import negocut.trade.dto.TradeActionResponses;
import negocut.trade.entity.Dispute;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.DisputeRepository;
import negocut.trade.repository.TradeCancelRequestRepository;
import negocut.trade.repository.TradeRepository;

// 분쟁 접수. (기능 명세서 3-9 분쟁 접수)
// 당사자가 준비·진행 상태의 거래에 접수하면 거래가 분쟁 상태가 되고, 처리 중인 취소 요청은 같은 트랜잭션에서 종결로 닫힌다.
// 거래 행을 먼저 잠그고 상태를 다시 확인하므로 구매 확정·취소 수락·다른 분쟁 접수와 겹쳐도 먼저 처리된 쪽만 성립한다.
// 분쟁 중에는 자동 구매 확정·지급 재시도·미이행 표시가 상태 조건 때문에 이 거래를 건너뛴다.
@Service
@RequiredArgsConstructor
public class TradeDisputeService {

    // 한 거래에서 당사자 한 명이 접수할 수 있는 분쟁 수. 판정이 끝난 분쟁도 센다.
    static final int MAX_DISPUTES_PER_PARTY = 3;

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final TradeRepository tradeRepository;
    private final DisputeRepository disputeRepository;
    private final TradeCancelRequestRepository cancelRequestRepository;
    private final MemberRepository memberRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TradeActionResponses.DisputeOpened open(Long memberId, Long tradeId, String reason) {
        Trade trade = tradeRepository.findByIdForUpdate(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        if (!trade.getSeller().getId().equals(memberId) && !trade.getBuyer().getId().equals(memberId)) {
            throw new BusinessException(ErrorCode.TRADE_ACCESS_DENIED);
        }
        if (disputeRepository.existsProcessingByTradeId(tradeId)) {
            throw new BusinessException(ErrorCode.DISPUTE_ALREADY_OPEN);
        }
        if (trade.getTradeStatus() != TradeStatus.READY && trade.getTradeStatus() != TradeStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TRADE_STATUS_INVALID);
        }
        if (disputeRepository.countByTradeIdAndReporterId(tradeId, memberId) >= MAX_DISPUTES_PER_PARTY) {
            throw new BusinessException(ErrorCode.DISPUTE_LIMIT_EXCEEDED);
        }

        Member reporter = memberRepository.getReferenceById(memberId);
        Dispute dispute = disputeRepository.save(Dispute.open(trade, reporter, reason));   // 접수 직전 상태를 먼저 저장한다.
        trade.openDispute();
        LocalDateTime now = LocalDateTime.now(ZONE);
        cancelRequestRepository.findProcessingByTradeId(tradeId).ifPresent(request -> request.close(now));
        return new TradeActionResponses.DisputeOpened(dispute.getId(), tradeId, trade.getTradeStatus());
    }
}
