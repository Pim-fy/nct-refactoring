package negocut.point.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// API 명세서 4-1 POST /api/members/me/points/charge
// 범위(1,000~100만)는 요청 형식에서 먼저 확인한다. 정수와 1,000 단위는 서비스가 확인한다.
public record PointChargeRequest(
        @NotNull(message = "충전 금액을 입력해 주세요.")
        @DecimalMin(value = "1000", message = "충전 금액은 1,000 포인트 이상이어야 합니다.")
        @DecimalMax(value = "1000000", message = "1회 충전 금액은 100만 포인트까지입니다.")
        BigDecimal amount) {
}
