package negocut.auction.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionResult;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Bid;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.AuctionResultRepository;
import negocut.auction.repository.BidRepository;
import negocut.trade.entity.Trade;
import negocut.trade.repository.TradeRepository;

// 경매 한 건의 종료 처리. 경매마다 하나의 트랜잭션으로 처리한다. (기능 명세서 3-4 경매 종료 처리, 3-7 낙찰)
// 다른 빈에서 부르므로 경매별로 트랜잭션이 나뉜다. 한 경매의 실패가 다른 경매를 되돌리지 않는다.
@Component
@RequiredArgsConstructor
public class AuctionCloseProcessor {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int SHIP_DUE_DAYS = 5;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final AuctionResultRepository auctionResultRepository;
    private final TradeRepository tradeRepository;

    // 경매 행을 잠그고 상태와 종료 시각을 다시 확인한 뒤 처리한다. 이미 종료되었거나 아직 종료 시각 전이면 아무것도 하지 않고 false를 돌려준다.
    // 같은 경매를 동시에 처리하려는 실행이 겹쳐도 먼저 잠근 쪽만 처리하고, 나머지는 잠금을 얻은 뒤 상태가 바뀐 것을 보고 건너뛴다.
    // 낙찰 정보와 거래는 경매당 한 건이며 유니크 제약이 한 번 더 막는다. 입찰도 경매 행을 먼저 잠그므로 종료 직전·직후의 입찰과 겹치지 않는다.
    // READ_COMMITTED로 실행해, 잠금을 기다린 사이에 커밋된 최고 입찰을 읽는다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean close(Long auctionId) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId).orElse(null);
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (auction == null || auction.getAuctionStatus() != AuctionStatus.IN_PROGRESS || auction.getEndAt().isAfter(now)) {
            return false;
        }

        if (auction.getTopBidId() == null) {
            // 유찰: 입찰이 없으므로 낙찰 정보와 거래를 만들지 않고, 해제할 예약도 없다.
            auction.close(AuctionStatus.FAILED, now);
            return true;
        }

        // 낙찰: 낙찰 금액은 종료 시점의 최고 입찰 금액이다. 낙찰자의 예약 포인트는 그대로 유지한다.
        Bid winningBid = bidRepository.findWithBidder(auction.getTopBidId())
                .orElseThrow(() -> new IllegalStateException("최고 입찰을 찾을 수 없습니다. auctionId=" + auctionId));
        AuctionResult result = auctionResultRepository.save(AuctionResult.confirm(auction, winningBid, now));
        tradeRepository.save(Trade.open(result, auction.getSeller(), winningBid.getBidder(), now.plusDays(SHIP_DUE_DAYS)));
        auction.close(AuctionStatus.SOLD, now);
        return true;
    }
}
