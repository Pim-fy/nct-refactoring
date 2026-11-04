package negocut.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminDisputeResponses;
import negocut.admin.dto.DisputeJudgeRequest;
import negocut.admin.dto.ReasonText;
import negocut.auction.repository.ProductImageRepository;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.member.repository.MemberRepository;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.service.PointService;
import negocut.trade.dto.TradeDetailResponse;
import negocut.trade.dto.TradeSummaryResponse;
import negocut.trade.entity.Dispute;
import negocut.trade.entity.DisputeJudgment;
import negocut.trade.entity.DisputeStatus;
import negocut.trade.entity.Trade;
import negocut.trade.entity.TradeStatus;
import negocut.trade.repository.DisputeRepository;
import negocut.trade.repository.TradeCancelRequestRepository;
import negocut.trade.repository.TradeDeliveryRepository;
import negocut.trade.repository.TradeRepository;
import negocut.trade.service.TradeConfirmProcessor;

// 관리자 분쟁 관리: 분쟁 목록, 분쟁 처리를 위한 거래 상세, 판정. (기능 명세서 3-10 분쟁 판정)
// 판정은 거래 행을 먼저 잠그고 분쟁을 다시 읽어 처리 중인지 확인한 뒤, 필요하면 잔액 행을 회원 번호 순서로 잠근다. (구매 확정·취소 수락과 같은 순서)
// 분쟁을 먼저 읽으면 같은 분쟁을 동시에 판정한 요청이 낡은 상태를 보고 포인트를 이중으로 움직일 수 있어서, 읽기 전에 거래 식별자만 알아 둔다.
// 포인트와 거래·분쟁 상태는 한 트랜잭션이라 지급·반환이 실패하면 거래는 분쟁 상태로 남는다.
@Service
@RequiredArgsConstructor
public class AdminDisputeService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;

    private final DisputeRepository disputeRepository;
    private final TradeRepository tradeRepository;
    private final TradeDeliveryRepository deliveryRepository;
    private final TradeCancelRequestRepository cancelRequestRepository;
    private final ProductImageRepository productImageRepository;
    private final MemberRepository memberRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final PointService pointService;
    private final TradeConfirmProcessor confirmProcessor;

    @Transactional(readOnly = true)
    public PageResult<AdminDisputeResponses.Item> list(DisputeStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Dispute> disputes = disputeRepository.searchForAdmin(status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.from(disputes.map(AdminDisputeResponses.Item::of));
    }

    // 분쟁 처리를 위한 거래 상세. 분쟁이 한 번이라도 접수된 거래만 볼 수 있고, 분쟁이 없는 거래는 없는 거래와 같이 404다.
    // (배송지·연락처 같은 개인정보를 분쟁 처리에 필요한 범위로 한정한다.) 처리 중인 분쟁에는 지금 거래 진행으로 판정하면 정해질 기한을 함께 담는다.
    @Transactional(readOnly = true)
    public AdminDisputeResponses.TradeDetail getTrade(Long tradeId) {
        Trade trade = tradeRepository.findDetailById(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        List<Dispute> found = disputeRepository.findAllByTradeId(tradeId);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.TRADE_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<AdminDisputeResponses.DisputeInfo> disputes = found.stream()
                .map(dispute -> AdminDisputeResponses.DisputeInfo.of(dispute, trade,
                        dispute.isProcessing() && trade.getTradeStatus() == TradeStatus.DISPUTED
                                ? AdminDisputeResponses.ResumeDue.of(trade.planResume(dispute.getPreviousTradeStatus(), now))
                                : null))
                .toList();
        Long productId = trade.getAuctionResult().getAuction().getProduct().getId();
        Map<Long, Long> thumbnails = productImageRepository.findThumbnails(List.of(productId)).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(), image -> image.getImageFile().getId(), (a, b) -> a));
        Long thumbnailId = thumbnails.get(productId);
        return new AdminDisputeResponses.TradeDetail(trade.getId(), trade.getTradeStatus(), trade.getTradePrice(),
                trade.getSeller().getNickname(), trade.getBuyer().getNickname(),
                new TradeDetailResponse.AuctionInfo(trade.getAuctionResult().getAuction().getId(),
                        trade.getAuctionResult().getAuction().getProduct().getName(), thumbnailId == null ? null : "/api/images/" + thumbnailId),
                TradeDetailResponse.Schedule.of(trade),
                deliveryRepository.findByTradeId(tradeId).map(TradeDetailResponse.DeliveryInfo::of).orElse(null),
                cancelRequestRepository.findProcessingByTradeId(tradeId).map(request -> TradeDetailResponse.CancelRequestInfo.of(request, trade)).orElse(null),
                disputes);
    }

    // READ_COMMITTED로 실행해, 거래 행을 기다린 사이에 커밋된 분쟁·잔액을 읽는다. (구매 확정·취소 수락과 같은 이유)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminDisputeResponses.Judged judge(Long adminId, Long disputeId, DisputeJudgeRequest request) {
        if (!ReasonText.isMeaningful(request.judgmentReason())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Long tradeId = disputeRepository.findTradeIdById(disputeId).orElseThrow(() -> new BusinessException(ErrorCode.DISPUTE_NOT_FOUND));
        Trade trade = tradeRepository.findByIdForUpdate(tradeId).orElseThrow(() -> new BusinessException(ErrorCode.TRADE_NOT_FOUND));
        Dispute dispute = disputeRepository.findById(disputeId).orElseThrow(() -> new BusinessException(ErrorCode.DISPUTE_NOT_FOUND));
        if (dispute.getDisputeStatus() != DisputeStatus.OPEN) {
            throw new BusinessException(ErrorCode.DISPUTE_ALREADY_CLOSED);
        }
        // 관리자가 본 마지막 판정 시각과 지금이 같아야 한다. 그사이 다른 관리자가 보류 유지로 판정했거나 같은 보류 유지를 먼저 보냈다면 거부하고,
        // 화면이 최신 판정 사유를 다시 보고 판단하게 한다. (보류 유지는 분쟁을 처리 중으로 남기므로 상태만으로는 막을 수 없다.)
        LocalDateTime expectedJudgedAt = request.expectedJudgedAt() == null ? null : request.expectedJudgedAt().atZoneSameInstant(ZONE).toLocalDateTime();
        if (!Objects.equals(expectedJudgedAt, dispute.getJudgedAt())) {
            throw new BusinessException(ErrorCode.DISPUTE_CHANGED);
        }
        if (trade.getTradeStatus() != TradeStatus.DISPUTED) {
            throw new BusinessException(ErrorCode.TRADE_STATUS_INVALID);
        }

        LocalDateTime now = LocalDateTime.now(ZONE);
        Trade.ResumeDue due = null;
        switch (request.judgment()) {
            case REFUND -> refund(trade, now);
            case PAYOUT -> {
                // 발송이 등록되지 않은 거래는 지급할 수 없다. 접수 직전 상태가 진행이었던 거래만 지급한다.
                if (dispute.getPreviousTradeStatus() != TradeStatus.IN_PROGRESS) {
                    throw new BusinessException(ErrorCode.DISPUTE_JUDGMENT_NOT_ALLOWED);
                }
                confirmProcessor.settlePayout(trade, now);
            }
            case RESUME -> due = trade.resume(dispute.getPreviousTradeStatus(), now);
            case HOLD -> { }   // 거래는 분쟁 상태로 두고 분쟁도 처리 중으로 남겨 다시 판정할 수 있게 한다.
        }
        dispute.judge(request.judgment(), request.judgmentReason(), memberRepository.getReferenceById(adminId), now);

        return new AdminDisputeResponses.Judged(dispute.getId(), dispute.getDisputeStatus(), dispute.getJudgment(), tradeId, trade.getTradeStatus(),
                TradeSummaryResponse.toOffset(now), due == null ? null : due.dueType(),
                due == null ? null : TradeSummaryResponse.toOffset(due.resumedDueAt()), due == null ? null : due.adjusted());
    }

    // 낙찰자의 예약 포인트를 돌려주고 거래를 취소로 바꾼다. 취소 수락과 같은 반환이다.
    private void refund(Trade trade, LocalDateTime now) {
        Long buyerId = trade.getBuyer().getId();
        PointBalance buyerBalance = pointBalanceRepository.findByMemberIdForUpdate(buyerId)
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + buyerId));
        pointService.refund(buyerBalance, trade.getBuyer(), trade.getTradePrice(), trade.getId());
        trade.cancel(now);
    }
}
