package negocut.auction.dto;

import java.math.BigDecimal;

import negocut.point.dto.PointResponse;

// API 명세서 4-1 입찰 응답. 갱신된 포인트를 함께 담아 화면이 포인트를 다시 조회하지 않게 한다.
public record BidResponse(
        Long bidId,
        Long auctionId,
        BigDecimal bidPrice,
        BigDecimal currentPrice,
        boolean isTopBidder,
        BigDecimal minimumBidPrice,
        PointResponse point) {
}
