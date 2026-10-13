package negocut.point.dto;

import java.math.BigDecimal;

import negocut.point.entity.PointBalance;

// API 명세서 4-1 포인트 조회·충전 응답. 사용 가능 포인트는 보유에서 예약을 뺀 값이다.
public record PointResponse(BigDecimal totalPoint, BigDecimal reservedPoint, BigDecimal availablePoint) {

    public static PointResponse from(PointBalance balance) {
        return new PointResponse(balance.getTotalPoint(), balance.getReservedPoint(), balance.availablePoint());
    }
}
