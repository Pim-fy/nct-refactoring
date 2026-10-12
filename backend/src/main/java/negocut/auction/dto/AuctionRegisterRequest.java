package negocut.auction.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// API 명세서 3-1 POST /api/auctions
// 형식·길이·범위는 여기서 확인하고(오류는 AUCTION_INPUT_INVALID와 필드별 사유), 시작가와 입찰 단위의 배수 관계,
// 종료 시각 범위, 이미지 소유·사용 여부는 서비스에서 확인한다.
public record AuctionRegisterRequest(
        @NotNull(message = "상품 정보를 입력해 주세요.") @Valid ProductRequest product,
        @NotNull(message = "거래 조건을 입력해 주세요.") @Valid TermsRequest auction) {

    public record ProductRequest(
            @NotBlank(message = "상품명을 입력해 주세요.")
            @Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
            String name,
            @NotNull(message = "카테고리를 선택해 주세요.")
            Long categoryId,
            @NotBlank(message = "상품 설명을 입력해 주세요.")
            @Size(max = 5000, message = "상품 설명은 5,000자 이하여야 합니다.")
            String description,
            @NotBlank(message = "상태 설명을 입력해 주세요.")
            @Size(max = 500, message = "상태 설명은 500자 이하여야 합니다.")
            String conditionNote,
            // 업로드로 받은 이미지 식별자를 순서대로 보낸다. 첫 번째가 대표 이미지다. 없거나 비어 있으면 AUCTION_IMAGE_REQUIRED다.
            List<Long> imageIds) {

        // 앞뒤 공백은 지워서 저장한다. 설명 안의 줄바꿈은 그대로 둔다.
        public ProductRequest {
            name = name == null ? null : name.trim();
            description = description == null ? null : description.trim();
            conditionNote = conditionNote == null ? null : conditionNote.trim();
        }
    }

    public record TermsRequest(
            @NotNull(message = "시작가를 입력해 주세요.")
            @DecimalMin(value = "100", message = "시작가는 100원 이상이어야 합니다.")
            @DecimalMax(value = "50000000", message = "시작가는 5,000만원 이하여야 합니다.")
            BigDecimal startPrice,
            @NotNull(message = "입찰 단위를 입력해 주세요.")
            @DecimalMin(value = "100", message = "입찰 단위는 100원 이상이어야 합니다.")
            @DecimalMax(value = "50000000", message = "입찰 단위는 5,000만원 이하여야 합니다.")
            BigDecimal bidUnit,
            @NotNull(message = "종료 시각을 입력해 주세요.")
            OffsetDateTime endAt) {
    }
}
