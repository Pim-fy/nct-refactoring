package negocut.auction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auction.dto.BidHistoryResponse;
import negocut.auction.dto.BidResponse;
import negocut.auction.dto.MyBidResponse;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.entity.Bid;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.BidRepository;
import negocut.auction.repository.ProductImageRepository;
import negocut.auth.security.AuthMember;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;
import negocut.member.entity.Member;
import negocut.member.repository.MemberRepository;
import negocut.point.dto.PointResponse;
import negocut.point.entity.PointBalance;
import negocut.point.repository.PointBalanceRepository;
import negocut.point.service.PointService;

// 입찰과 입찰 내역, 내 입찰 목록. (기능 명세서 3-6)
@Service
@RequiredArgsConstructor
public class BidService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final ProductImageRepository productImageRepository;
    private final PointBalanceRepository pointBalanceRepository;
    private final MemberRepository memberRepository;
    private final PointService pointService;
    private final AuctionQueryService auctionQueryService;

    // 동시 입찰 정합성은 비관적 잠금으로 지킨다. (기능 명세서 D-3)
    // 순서: 경매 행 → 입찰자와 직전 최고 입찰자의 잔액 행(회원 번호 순서). 경매 행을 먼저 잠가 같은 경매의 입찰을 한 건씩 처리하고,
    // 잔액 행은 항상 같은 순서로 잠가 서로 다른 경매의 입찰끼리 교착 상태가 되지 않게 한다. 충전은 잔액 행 하나만 잠근다.
    // READ_COMMITTED로 실행해, 잠금을 기다린 사이에 다른 요청이 커밋한 현재가와 잔액을 읽는다.
    // (REPEATABLE READ에서는 첫 일반 조회 시점에 읽기 스냅샷이 고정될 수 있다. 경매 행을 가장 먼저 읽는다.)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BidResponse placeBid(AuthMember bidder, Long auctionId, BigDecimal bidPrice) {
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(ZONE);
        // 진행 상태이고 종료 시각 전이어야 한다. 종료 시각이 지난 경매는 종료 처리를 기다리는 중이라도 입찰을 받지 않는다.
        if (auction.getAuctionStatus() != AuctionStatus.IN_PROGRESS || !now.isBefore(auction.getEndAt())) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_IN_PROGRESS);
        }
        if (auction.getSeller().getId().equals(bidder.memberId())) {
            throw new BusinessException(ErrorCode.BID_SELF_AUCTION_NOT_ALLOWED);
        }
        requireValidPrice(auction, bidPrice);

        // 직전 최고 입찰과 그 입찰자. 최고 입찰자가 본인이면 예약을 유지한 채 차액만 더 예약한다.
        Bid previousTop = auction.getTopBidId() == null ? null : bidRepository.findWithBidder(auction.getTopBidId()).orElseThrow();
        boolean selfTop = previousTop != null && previousTop.getBidder().getId().equals(bidder.memberId());

        // 회원 번호 순서로 한 명씩 잠근다. IN 조건 하나로 읽으면 실제 잠금 순서가 실행 계획에 따라 달라질 수 있어, 순서를 코드로 정한다.
        Set<Long> memberIds = new TreeSet<>();
        memberIds.add(bidder.memberId());
        if (previousTop != null) {
            memberIds.add(previousTop.getBidder().getId());
        }
        Map<Long, PointBalance> balances = new HashMap<>();
        for (Long memberId : memberIds) {
            balances.put(memberId, pointBalanceRepository.findByMemberIdForUpdate(memberId)
                    .orElseThrow(() -> new IllegalStateException("포인트 잔액이 없는 회원입니다. memberId=" + memberId)));
        }
        PointBalance bidderBalance = balances.get(bidder.memberId());

        // 예약에 필요한 금액: 최고 입찰자가 본인이면 입찰 금액에서 기존 예약을 뺀 값, 그 밖에는 입찰 금액 전체
        BigDecimal needed = selfTop ? bidPrice.subtract(previousTop.getBidPrice()) : bidPrice;
        Member bidderMember = memberRepository.getReferenceById(bidder.memberId());
        pointService.reserve(bidderBalance, bidderMember, needed, auction);

        if (previousTop != null) {
            previousTop.releaseReservation();   // 예약은 새 입찰로 넘어간다.
            if (!selfTop) {
                PointBalance previousBalance = balances.get(previousTop.getBidder().getId());
                pointService.release(previousBalance, previousTop.getBidder(), previousTop.getBidPrice(), auction);
            }
        }

        Bid bid = bidRepository.save(Bid.place(auction, bidderMember, bidPrice, now));
        auction.applyBid(bid);

        return new BidResponse(bid.getId(), auction.getId(), bidPrice, auction.getCurrentPrice(), true,
                bidPrice.add(auction.getBidUnit()), PointResponse.from(bidderBalance));
    }

    // 공개 입찰 내역. 입찰자는 일부를 가린다. 중단된 경매는 상세와 같은 사람만 볼 수 있다.
    @Transactional(readOnly = true)
    public PageResult<BidHistoryResponse> getBids(Long auctionId, AuthMember viewer, int page, int size) {
        requirePage(page, size);
        Auction auction = auctionQueryService.findVisible(auctionId, viewer);
        Long viewerId = viewer == null ? null : viewer.memberId();
        Page<Bid> bids = bidRepository.findByAuctionId(auction.getId(), PageRequest.of(page, size));
        return PageResult.from(bids.map(bid -> BidHistoryResponse.of(bid, viewerId)));
    }

    // 내 입찰 목록. 경매마다 한 줄이고, 내가 마지막으로 입찰한 경매가 먼저다.
    @Transactional(readOnly = true)
    public PageResult<MyBidResponse> getMyBids(Long memberId, int page, int size) {
        requirePage(page, size);
        Page<Auction> auctions = auctionRepository.findBidOnBy(memberId, PageRequest.of(page, size));

        List<Long> auctionIds = auctions.getContent().stream().map(auction -> auction.getId()).toList();
        List<Long> productIds = auctions.getContent().stream().map(auction -> auction.getProduct().getId()).toList();
        List<Long> topBidIds = auctions.getContent().stream().map(auction -> auction.getTopBidId()).filter(id -> id != null).toList();

        Map<Long, BigDecimal> myPrices = auctionIds.isEmpty() ? Map.of() : bidRepository.findMyBidPrices(memberId, auctionIds).stream()
                .collect(Collectors.toMap(price -> price.getAuctionId(), price -> price.getMyBidPrice()));
        Set<Long> reserved = auctionIds.isEmpty() ? Set.of() : new HashSet<>(bidRepository.findReservedAuctionIds(memberId, auctionIds));
        Set<Long> myTopBidIds = topBidIds.isEmpty() ? Set.of() : new HashSet<>(bidRepository.findIdsByBidder(memberId, topBidIds));
        Map<Long, Long> thumbnails = productIds.isEmpty() ? Map.of() : productImageRepository.findThumbnails(productIds).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(), image -> image.getImageFile().getId()));

        LocalDateTime now = LocalDateTime.now(ZONE);
        return PageResult.from(auctions.map(auction -> MyBidResponse.of(
                auction,
                thumbnails.get(auction.getProduct().getId()),
                myPrices.get(auction.getId()),
                auction.getTopBidId() != null && myTopBidIds.contains(auction.getTopBidId()),
                reserved.contains(auction.getId()),
                now)));
    }

    // 입찰 금액은 입찰 단위의 배수이고 최소 입찰 금액 이상이어야 한다.
    // 첫 입찰의 최소 금액은 시작가이고, 그 뒤에는 현재가에 입찰 단위를 더한 값이다.
    // 100000.0이나 1E+5 같은 표기는 같은 금액을 다른 요청으로 보이게 하므로 정수 표기만 받는다.
    private void requireValidPrice(Auction auction, BigDecimal bidPrice) {
        if (bidPrice.scale() != 0 || bidPrice.remainder(auction.getBidUnit()).signum() != 0) {
            throw new BusinessException(ErrorCode.BID_UNIT_MISMATCH);
        }
        BigDecimal minimum = auction.getCurrentPrice() == null
                ? auction.getStartPrice()
                : auction.getCurrentPrice().add(auction.getBidUnit());
        if (bidPrice.compareTo(minimum) < 0) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW);
        }
    }

    private void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
