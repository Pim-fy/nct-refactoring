package negocut.auction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import negocut.auction.dto.AuctionRegisterRequest;
import negocut.auction.dto.AuctionRegisterResponse;
import negocut.auction.dto.AuctionSummaryResponse;
import negocut.auction.entity.Auction;
import negocut.auction.entity.Category;
import negocut.auction.entity.Product;
import negocut.auction.entity.ProductImage;
import negocut.auction.repository.AuctionRepository;
import negocut.auction.repository.CategoryRepository;
import negocut.auction.repository.ProductImageRepository;
import negocut.auction.repository.ProductRepository;
import negocut.auth.security.AuthMember;
import negocut.common.exception.BusinessException;
import negocut.common.exception.ErrorCode;
import negocut.common.exception.InputInvalidException;
import negocut.common.response.ErrorResult;
import negocut.common.response.PageResult;
import negocut.file.entity.ImageFile;
import negocut.file.entity.ImageUsage;
import negocut.file.repository.ImageFileRepository;
import negocut.member.entity.Member;
import negocut.member.repository.MemberRepository;

// 경매 등록과 내 경매 목록. (기능 명세서 3-4)
@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final BigDecimal PRICE_STEP = BigDecimal.valueOf(100);
    private static final int MAX_IMAGES = 10;
    // 종료 시각은 등록 시각 기준 10분 뒤부터 30일 이내다.
    private static final int MIN_END_MINUTES = 10;
    private static final int MAX_END_DAYS = 30;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuctionRepository auctionRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;
    private final ImageFileRepository imageFileRepository;
    private final MemberRepository memberRepository;

    // READ_COMMITTED로 실행한다. MySQL 기본 격리 수준(REPEATABLE READ)에서는 트랜잭션의 첫 일반 조회 시점에 읽기 스냅샷이 고정되어,
    // 이미지 행을 잠근 뒤에도 그 사이에 다른 요청이 커밋한 "이미지 사용" 결과를 보지 못할 수 있다.
    // 같은 이미지를 동시에 여러 경매에 연결하는 일을 막으려면 잠금 이후에 최신 커밋 상태를 읽어야 한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AuctionRegisterResponse register(AuthMember seller, AuctionRegisterRequest request) {
        AuctionRegisterRequest.ProductRequest productRequest = request.product();
        AuctionRegisterRequest.TermsRequest terms = request.auction();

        validatePrices(terms.startPrice(), terms.bidUnit());
        LocalDateTime endAt = requireValidEndAt(terms.endAt().atZoneSameInstant(ZONE).toLocalDateTime());
        List<Long> imageIds = requireImageIds(productRequest.imageIds());

        // 이미지 행을 잠그고 확인한다. 같은 이미지를 지우거나 다른 경매에 연결하는 요청과 겹쳐도 한쪽만 성립한다.
        // 다른 조회보다 먼저, 첫 데이터베이스 작업으로 잠근다.
        List<ImageFile> images = lockAndVerifyImages(imageIds, seller.memberId());
        Category category = requireUsableCategory(productRequest.categoryId());

        Member sellerMember = memberRepository.getReferenceById(seller.memberId());
        Product product = productRepository.save(Product.create(
                category, productRequest.name(), productRequest.description(), productRequest.conditionNote()));
        List<ProductImage> productImages = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            productImages.add(ProductImage.create(product, images.get(i), i + 1));   // 순서 1이 대표 이미지다.
        }
        productImageRepository.saveAll(productImages);

        Auction auction = auctionRepository.save(Auction.register(
                product, sellerMember, terms.startPrice(), terms.bidUnit(), endAt));
        return new AuctionRegisterResponse(auction.getId());
    }

    @Transactional(readOnly = true)
    public PageResult<AuctionSummaryResponse> getMyAuctions(Long memberId, int page, int size) {
        // 첫 행의 위치(page * size)가 int 범위를 넘으면 조회할 수 없으므로 범위 밖으로 본다.
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Page<Auction> auctions = auctionRepository.findBySellerId(memberId, PageRequest.of(page, size));

        List<Long> productIds = auctions.getContent().stream().map(auction -> auction.getProduct().getId()).toList();
        Map<Long, Long> thumbnails = productIds.isEmpty() ? Map.of() : productImageRepository.findThumbnails(productIds).stream()
                .collect(Collectors.toMap(image -> image.getProduct().getId(), image -> image.getImageFile().getId()));

        LocalDateTime now = LocalDateTime.now(ZONE);
        return PageResult.from(auctions.map(
                auction -> AuctionSummaryResponse.of(auction, thumbnails.get(auction.getProduct().getId()), now)));
    }

    // 시작가는 100원 이상 5,000만원 이하(요청 형식에서 확인)이고 100원 단위여야 한다. 입찰 단위도 100원 단위여야 하며,
    // 시작가는 입찰 단위의 배수여야 한다. 배수가 아니면 아무도 입찰할 수 없는 경매가 되기 때문이다.
    private void validatePrices(BigDecimal startPrice, BigDecimal bidUnit) {
        List<ErrorResult.FieldError> errors = new ArrayList<>();
        boolean startOk = startPrice.remainder(PRICE_STEP).signum() == 0;
        boolean unitOk = bidUnit.remainder(PRICE_STEP).signum() == 0;
        if (!startOk) {
            errors.add(new ErrorResult.FieldError("auction.startPrice", "시작가는 100원 단위여야 합니다."));
        }
        if (!unitOk) {
            errors.add(new ErrorResult.FieldError("auction.bidUnit", "입찰 단위는 100원 단위여야 합니다."));
        }
        if (startOk && unitOk && startPrice.remainder(bidUnit).signum() != 0) {
            errors.add(new ErrorResult.FieldError("auction.startPrice", "시작가는 입찰 단위의 배수여야 합니다."));
        }
        if (!errors.isEmpty()) {
            throw new InputInvalidException(ErrorCode.AUCTION_INPUT_INVALID, errors);
        }
    }

    private LocalDateTime requireValidEndAt(LocalDateTime endAt) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (endAt.isBefore(now.plusMinutes(MIN_END_MINUTES)) || endAt.isAfter(now.plusDays(MAX_END_DAYS))) {
            throw new BusinessException(ErrorCode.AUCTION_END_TIME_INVALID);
        }
        return endAt;
    }

    private List<Long> requireImageIds(List<Long> imageIds) {
        if (imageIds == null || imageIds.isEmpty()) {
            throw new BusinessException(ErrorCode.AUCTION_IMAGE_REQUIRED);
        }
        if (imageIds.size() > MAX_IMAGES) {
            throw imageError("상품 이미지는 최대 10장까지 사용할 수 있습니다.");
        }
        Set<Long> unique = new HashSet<>();
        for (Long id : imageIds) {
            if (id == null || !unique.add(id)) {
                throw imageError("이미지 식별자가 비어 있거나 중복되었습니다.");
            }
        }
        return imageIds;
    }

    // 카테고리 행을 공유 잠금으로 읽는다. 삭제는 배타 잠금을 쓰므로, 등록이 끝나기 전에는 삭제되지 않고 삭제가 끝난 뒤에는 이 조회에서 걸러진다.
    private Category requireUsableCategory(Long categoryId) {
        return categoryRepository.findByIdForShare(categoryId)
                .filter(category -> category.isActive())
                .orElseThrow(() -> new InputInvalidException(ErrorCode.AUCTION_INPUT_INVALID,
                        List.of(new ErrorResult.FieldError("product.categoryId", "선택할 수 없는 카테고리입니다."))));
    }

    // 요청한 이미지가 모두 있고(삭제되지 않음) 상품 이미지이며 본인이 올렸고 아직 어떤 경매에도 쓰이지 않았는지 확인한다.
    // 남의 이미지는 없는 이미지와 같은 오류로 응답해 존재 여부를 드러내지 않는다. 요청 순서대로 돌려준다.
    private List<ImageFile> lockAndVerifyImages(List<Long> imageIds, Long sellerId) {
        Map<Long, ImageFile> found = imageFileRepository.findAllByIdAndUploaderIdForUpdate(imageIds, sellerId).stream()
                .collect(Collectors.toMap(image -> image.getId(), image -> image));

        List<ImageFile> ordered = new ArrayList<>();
        for (Long id : imageIds) {
            ImageFile image = found.get(id);
            if (image == null || image.isDeleted() || image.getUsageType() != ImageUsage.PRODUCT) {
                throw imageError("사용할 수 없는 이미지가 있습니다.");
            }
            ordered.add(image);
        }
        if (!productImageRepository.findUsedImageFileIds(imageIds).isEmpty()) {
            throw imageError("이미 다른 경매에 사용한 이미지가 있습니다.");
        }
        return ordered;
    }

    private InputInvalidException imageError(String message) {
        return new InputInvalidException(ErrorCode.AUCTION_INPUT_INVALID,
                List.of(new ErrorResult.FieldError("product.imageIds", message)));
    }
}
