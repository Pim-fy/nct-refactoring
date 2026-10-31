package negocut.admin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.trade.dto.TradeSummaryResponse;

// API 명세서 7-1 관리자 경매 관리 응답들. 관리자만 보므로 판매자 닉네임을 가리지 않는다.
public final class AdminAuctionResponses {

    private AdminAuctionResponses() {
    }

    // GET /api/admin/auctions 의 항목. 입찰이 없으면 currentPrice는 null이고, stoppedReason은 중단된 경매에만 있다.
    public record Item(Long auctionId, String productName, String sellerNickname, BigDecimal currentPrice, int bidCount,
            OffsetDateTime endAt, AuctionStatus auctionStatus, String stoppedReason) {

        public static Item of(Auction auction) {
            return new Item(auction.getId(), auction.getProduct().getName(), auction.getSeller().getNickname(), auction.getCurrentPrice(),
                    auction.getBidCount(), TradeSummaryResponse.toOffset(auction.getEndAt()), auction.getAuctionStatus(),
                    auction.getAuctionStatus() == AuctionStatus.STOPPED ? auction.getStoppedReason() : null);
        }
    }

    // POST /api/admin/auctions/{id}/stop. releasedReservationCount는 이 중단으로 해제한 예약의 수다.
    public record Stopped(Long auctionId, AuctionStatus auctionStatus, OffsetDateTime stoppedAt, int releasedReservationCount) {
    }
}
