package negocut.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.admin.dto.AdminAuctionResponses;
import negocut.admin.dto.AuctionStopRequest;
import negocut.admin.dto.ReasonText;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Bid;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.BidRepository;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.service.PointService;
import negocut.trade.dto.TradeSummaryResponse;

// 관리자 경매 관리: 경매 목록(중단 포함)과 강제 종료·숨김. (기능 명세서 3-10 경매 강제 종료·숨김)
// 등록 또는 진행 중인 경매를 중단으로 바꾸고 남은 예약 포인트를 해제한다. 사유·관리자·시각은 경매에 남긴다.
// 입찰·종료 처리와 같이 경매 행을 가장 먼저 잠근 뒤 상태를 다시 확인하므로, 겹쳐도 먼저 잠근 쪽만 성립한다.
// 예약은 최고 입찰 한 건만 잡혀 있으며 그 입찰자의 잔액 행을 잠근 뒤 푼다. (경매 → 잔액 순서는 입찰 처리와 같다.)
// 종료 시각이 지났지만 종료 처리 전인 진행 중 경매도 관리자 조치이므로 중단할 수 있다.
@Service
@RequiredArgsConstructor
public class AdminAuctionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final PointService pointService;

    @Transactional(readOnly = true)
    public PageResult<AdminAuctionResponses.Item> list(AuctionStatus status, String keyword, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE
                || (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Auction> auctions = auctionRepository.searchForAdmin(status, likePattern(keyword),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")));
        return PageResult.from(auctions.map(AdminAuctionResponses.Item::of));
    }

    // READ_COMMITTED로 실행해, 잠금을 기다린 사이에 커밋된 최고 입찰과 잔액을 읽는다. (입찰·종료 처리와 같은 이유)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminAuctionResponses.Stopped stop(Long adminId, Long auctionId, AuctionStopRequest request) {
        if (!ReasonText.isMeaningful(request.stoppedReason())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        if (auction.getAuctionStatus() != AuctionStatus.REGISTERED && auction.getAuctionStatus() != AuctionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.AUCTION_STATUS_INVALID);
        }

        int released = releaseReservation(auction);
        LocalDateTime now = LocalDateTime.now(ZONE);
        auction.stop(request.stoppedReason(), adminId, now);
        return new AdminAuctionResponses.Stopped(auction.getId(), auction.getAuctionStatus(), TradeSummaryResponse.toOffset(now), released);
    }

    // 최고 입찰의 예약 포인트를 푼다. 입찰이 없거나 이미 풀려 있으면 아무것도 하지 않는다. 푼 예약의 수를 돌려준다.
    private int releaseReservation(Auction auction) {
        if (auction.getTopBidId() == null) {
            return 0;
        }
        Bid topBid = bidRepository.findWithBidder(auction.getTopBidId())
                .orElseThrow(() -> new IllegalStateException("최고 입찰을 찾을 수 없습니다. auctionId=" + auction.getId()));
        if (!topBid.isReserved()) {
            return 0;
        }
        PointBalance balance = pointBalanceRepository.findByMemberIdForUpdate(topBid.getBidder().getId())
                .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + topBid.getBidder().getId()));
        pointService.release(balance, topBid.getBidder(), topBid.getBidPrice(), auction);
        topBid.releaseReservation();
        return 1;
    }

    // 검색어를 포함 검색 패턴으로 바꾼다. 앞뒤 공백을 지우고 LIKE의 특수 문자(%, _)와 이스케이프 문자(!)는 글자 그대로 찾는다.
    private String likePattern(String keyword) {
        String trimmed = keyword == null ? "" : keyword.strip();
        return "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
