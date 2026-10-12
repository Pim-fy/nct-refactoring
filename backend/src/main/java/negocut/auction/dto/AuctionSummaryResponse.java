package negocut.auction.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;

// API 명세서 3-1 경매 목록의 한 항목. 내 경매 목록도 같은 형식이다.
public record AuctionSummaryResponse(
        Long auctionId,
        String productName,
        String categoryName,
        String thumbnailUrl,
        BigDecimal startPrice,
        BigDecimal currentPrice,
        int bidCount,
        OffsetDateTime endAt,
        long remainingSeconds,
        AuctionStatus auctionStatus) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // thumbnailImageId는 상품의 대표 이미지 식별자다.
    public static AuctionSummaryResponse of(Auction auction, Long thumbnailImageId, LocalDateTime now) {
        return new AuctionSummaryResponse(
                auction.getId(),
                auction.getProduct().getName(),
                auction.getProduct().getCategory().getName(),
                thumbnailImageId == null ? null : "/api/images/" + thumbnailImageId,
                auction.getStartPrice(),
                auction.getCurrentPrice(),
                auction.getBidCount(),
                auction.getEndAt().atZone(ZONE).toOffsetDateTime(),
                Duration.between(now, auction.getEndAt()).toSeconds(),   // 0 이하이면 종료 처리를 기다리는 상태다.
                auction.getAuctionStatus());
    }
}
