package negocut.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// API 명세서 5-2 POST /api/trades/{id}/shipment. 앞뒤 공백은 지운다.
public record ShipmentRequest(
        @NotBlank(message = "택배사를 입력해 주세요.")
        @Size(max = 50, message = "택배사는 50자 이하로 입력해 주세요.")
        String courierName,

        @NotBlank(message = "운송장 번호를 입력해 주세요.")
        @Size(max = 50, message = "운송장 번호는 50자 이하로 입력해 주세요.")
        @Pattern(regexp = "^[0-9A-Za-z-]*$", message = "운송장 번호는 영문, 숫자, 하이픈으로 입력해 주세요.")
        String trackingNumber) {

    public ShipmentRequest {
        courierName = courierName == null ? null : courierName.trim();
        trackingNumber = trackingNumber == null ? null : trackingNumber.trim();
    }
}
