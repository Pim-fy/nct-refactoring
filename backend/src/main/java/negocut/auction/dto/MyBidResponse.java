package negocut.auction.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;

// API 명세서 4-1 내 입찰 목록의 한 항목. 경매마다 한 줄이다.
public record MyBidResponse(
        Long auctionId,
        String productName,
        String thumbnailUrl,
        BigDecimal myBidPrice,
        BigDecimal currentPrice,
        boolean isTopBidder,
        boolean isReserved,
        OffsetDateTime endAt,
        long remainingSeconds,
        AuctionStatus auctionStatus) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // myBidPrice는 이 경매에서 본인이 넣은 가장 높은 입찰 금액이다.
    public static MyBidResponse of(Auction auction, Long thumbnailImageId, BigDecimal myBidPrice,
            boolean isTopBidder, boolean isReserved, LocalDateTime now) {
        return new MyBidResponse(
                auction.getId(),
                auction.getProduct().getName(),
                thumbnailImageId == null ? null : "/api/images/" + thumbnailImageId,
                myBidPrice,
                auction.getCurrentPrice(),
                isTopBidder,
                isReserved,
                auction.getEndAt().atZone(ZONE).toOffsetDateTime(),
                Duration.between(now, auction.getEndAt()).toSeconds(),
                auction.getAuctionStatus());
    }
}
