package negocut.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import negocut.auction.entity.Bid;
import negocut.common.util.Nicknames;

// API 명세서 3-1 입찰 내역의 한 항목. 입찰자는 일부를 가려서 보여 준다.
public record BidHistoryResponse(
        Long bidId,
        BigDecimal bidPrice,
        OffsetDateTime bidAt,
        String maskedBidderNickname,
        boolean isMine) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // viewerId는 로그인하지 않았으면 null이다.
    public static BidHistoryResponse of(Bid bid, Long viewerId) {
        return new BidHistoryResponse(
                bid.getId(),
                bid.getBidPrice(),
                bid.getBidAt().atZone(ZONE).toOffsetDateTime(),
                Nicknames.mask(bid.getBidder().getNickname()),
                viewerId != null && viewerId.equals(bid.getBidder().getId()));
    }
}
