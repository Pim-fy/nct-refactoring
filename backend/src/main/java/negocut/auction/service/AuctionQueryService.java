package negocut.auction.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auction.dto.AuctionDetailResponse;
import negocut.auction.dto.AuctionSummaryResponse;
import negocut.auction.entity.Auction;
import negocut.auction.entity.AuctionResult;
import negocut.auction.entity.AuctionSort;
import negocut.auction.entity.AuctionStatus;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.AuctionSearchRepository;
import negocut.auction.repository.AuctionResultRepository;
import negocut.auction.repository.BidRepository;
import negocut.auction.repository.ProductImageRepository;
import negocut.auth.security.AuthMember;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.response.PageResult;

// 경매 목록과 상세 조회. (기능 명세서 3-4)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuctionQueryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final char ESCAPE = '!';
    private static final String EDGE_SPACES = "^[\\p{Z}\\s]+|[\\p{Z}\\s]+$";

    private final AuctionRepository auctionRepository;
    private final AuctionSearchRepository auctionSearchRepository;
    private final ProductImageRepository productImageRepository;
    private final BidRepository bidRepository;
    private final AuctionResultRepository auctionResultRepository;

    // 공개 목록. 중단된 경매는 보이지 않는다. 검색은 상품명과 상품 설명에서 찾는다.
    public PageResult<AuctionSummaryResponse> search(Long categoryId, String keyword, String sortValue, int page, int size) {
        AuctionSort sort = AuctionSort.from(sortValue);
        if (sort == null || page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String trimmed = keyword == null ? "" : keyword.replaceAll(EDGE_SPACES, "");   // 전각 공백·NBSP 같은 유니코드 공백도 앞뒤에서 지운다. (String.trim·strip은 NBSP를 남긴다.)
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Page<Auction> auctions = auctionSearchRepository.search(
                categoryId, trimmed.isEmpty() ? null : likePattern(trimmed), sort, PageRequest.of(page, size));
        List<Long> productIds = auctions.getContent().stream().map(auction -> auction.getProduct().getId()).toList();
        Map<Long, Long> thumbnails = productIds.isEmpty() ? Map.of() : productImageRepository.findThumbnails(productIds).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(), image -> image.getImageFile().getId()));

        LocalDateTime now = LocalDateTime.now(ZONE);
        return PageResult.from(auctions.map(
                auction -> AuctionSummaryResponse.of(auction, thumbnails.get(auction.getProduct().getId()), now)));
    }

    // 공개 상세. viewer는 로그인하지 않았으면 null이다.
    public AuctionDetailResponse getDetail(Long auctionId, AuthMember viewer) {
        Auction auction = findVisible(auctionId, viewer);
        AuctionResult result = auction.getAuctionStatus() == AuctionStatus.SOLD
                ? auctionResultRepository.findByAuctionId(auction.getId()).orElse(null)
                : null;
        return AuctionDetailResponse.of(auction, productImageRepository.findByProductId(auction.getProduct().getId()),
                viewer, isTopBidder(auction, viewer), result, LocalDateTime.now(ZONE));
    }

    // 보는 사람이 열 수 있는 경매를 읽는다. 없는 경매와, 중단된 경매를 열 수 없는 사람은 같은 404다.
    // 중단된 경매는 판매자와 입찰한 적이 있는 회원만 볼 수 있다. viewer는 로그인하지 않았으면 null이다.
    public Auction findVisible(Long auctionId, AuthMember viewer) {
        Auction auction = auctionRepository.findDetailById(auctionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));
        if (auction.getAuctionStatus() == AuctionStatus.STOPPED
                && (viewer == null || !(viewer.memberId().equals(auction.getSeller().getId())
                        || bidRepository.existsByAuctionIdAndBidderId(auction.getId(), viewer.memberId())))) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_FOUND);
        }
        return auction;
    }

    // 현재 최고 입찰자가 보는 사람 본인인지
    private boolean isTopBidder(Auction auction, AuthMember viewer) {
        if (viewer == null || auction.getTopBidId() == null) {
            return false;
        }
        return bidRepository.findWithBidder(auction.getTopBidId())
                .map(bid -> bid.getBidder().getId().equals(viewer.memberId()))
                .orElse(false);
    }

    // 검색어의 %, _, 이스케이프 문자는 글자 그대로 찾도록 이스케이프한다.
    private String likePattern(String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        StringBuilder escaped = new StringBuilder("%");
        for (char c : lower.toCharArray()) {
            if (c == '%' || c == '_' || c == ESCAPE) {
                escaped.append(ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.append('%').toString();
    }
}
