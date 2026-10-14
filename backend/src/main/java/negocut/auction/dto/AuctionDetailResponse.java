package negocut.auction.dto;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.ProductImage;
import negocut.auth.security.AuthMember;
import negocut.common.util.Nicknames;
import negocut.member.entity.MemberRole;
import negocut.member.entity.MemberStatus;

// API 명세서 3-1 경매 상세. 입찰 내역과 낙찰 결과(result)는 입찰·낙찰 구현에서 채운다.
public record AuctionDetailResponse(
        Long auctionId,
        AuctionStatus auctionStatus,
        SellerInfo seller,
        ProductInfo product,
        TermsInfo terms,
        BiddingInfo bidding,
        Object result,
        String stoppedReason) {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public record SellerInfo(String maskedNickname, boolean isMine) {
    }

    public record ImageInfo(Long imageId, String url, int sortOrder) {
    }

    public record ProductInfo(String name, String categoryName, String description, String conditionNote, List<ImageInfo> images) {
    }

    public record TermsInfo(BigDecimal startPrice, BigDecimal bidUnit, OffsetDateTime endAt,
            String deliveryMethod, String shippingFeeBearer) {
    }

    // isTopBidder는 로그인한 보는 사람이 현재 최고 입찰자일 때만 참이다.
    public record BiddingInfo(BigDecimal currentPrice, int bidCount, BigDecimal minimumBidPrice,
            long remainingSeconds, boolean canBid, boolean isTopBidder) {
    }

    // viewer는 로그인하지 않았으면 null이다. stoppedReason은 호출하는 쪽이 볼 수 있는 경우에만 값을 넘긴다.
    public static AuctionDetailResponse of(Auction auction, List<ProductImage> images, AuthMember viewer,
            boolean isTopBidder, LocalDateTime now) {
        boolean isSeller = viewer != null && viewer.memberId().equals(auction.getSeller().getId());
        long remainingSeconds = Duration.between(now, auction.getEndAt()).toSeconds();   // 0 이하이면 종료 처리를 기다리는 상태다.
        BigDecimal currentPrice = auction.getCurrentPrice();
        // 첫 입찰은 시작가, 그 뒤에는 현재가에 입찰 단위를 더한 값이다. (기능 명세서 3-6)
        BigDecimal minimumBidPrice = currentPrice == null ? auction.getStartPrice() : currentPrice.add(auction.getBidUnit());

        return new AuctionDetailResponse(
                auction.getId(),
                auction.getAuctionStatus(),
                new SellerInfo(Nicknames.mask(auction.getSeller().getNickname()), isSeller),
                new ProductInfo(
                        auction.getProduct().getName(),
                        auction.getProduct().getCategory().getName(),
                        auction.getProduct().getDescription(),
                        auction.getProduct().getConditionNote(),
                        images.stream()
                                .map(image -> new ImageInfo(image.getImageFile().getId(),
                                        "/api/images/" + image.getImageFile().getId(), image.getSortOrder()))
                                .toList()),
                new TermsInfo(auction.getStartPrice(), auction.getBidUnit(),
                        auction.getEndAt().atZone(ZONE).toOffsetDateTime(), "PARCEL", "SELLER"),
                new BiddingInfo(currentPrice, auction.getBidCount(), minimumBidPrice, remainingSeconds,
                        canBid(auction, viewer, isSeller, remainingSeconds), isTopBidder),
                null,
                auction.getAuctionStatus() == AuctionStatus.STOPPED ? auction.getStoppedReason() : null);
    }

    // 로그인한 정상 회원이고, 판매자 본인이 아니며, 진행 중이고 마감 전일 때만 입찰할 수 있다.
    private static boolean canBid(Auction auction, AuthMember viewer, boolean isSeller, long remainingSeconds) {
        return viewer != null
                && viewer.role() == MemberRole.MEMBER
                && viewer.status() == MemberStatus.NORMAL
                && !isSeller
                && auction.getAuctionStatus() == AuctionStatus.IN_PROGRESS
                && remainingSeconds > 0;
    }
}
