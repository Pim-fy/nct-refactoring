package negocut.auction.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// API 명세서 4-1 POST /api/auctions/{id}/bids
// 최소 입찰 금액과 입찰 단위는 경매마다 달라 서비스가 확인한다. 여기서는 양수이고 터무니없이 크지 않은지만 본다.
// 상한은 지수 표기로 아주 큰 값이 나머지 계산까지 가지 않게 하기 위한 것이다.
public record BidRequest(
        @NotNull(message = "입찰 금액을 입력해 주세요.")
        @DecimalMin(value = "1", message = "입찰 금액은 1원 이상이어야 합니다.")
        @DecimalMax(value = "1000000000", message = "입찰 금액은 10억원 이하여야 합니다.")
        BigDecimal bidPrice) {
}
