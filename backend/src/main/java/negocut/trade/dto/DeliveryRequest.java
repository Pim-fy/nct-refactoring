package negocut.trade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// API 명세서 5-2 PUT /api/trades/{id}/delivery. 앞뒤 공백은 지운다. 길이는 DB 설계서 2-6을 따른다.
public record DeliveryRequest(
        @NotBlank(message = "수령인을 입력해 주세요.")
        @Size(max = 50, message = "수령인은 50자 이하로 입력해 주세요.")
        String receiverName,

        @NotBlank(message = "연락처를 입력해 주세요.")
        @Pattern(regexp = "^(?=.{9,20}$)[0-9]+(-[0-9]+)*$", message = "연락처는 숫자와 하이픈으로 입력해 주세요.")
        String receiverPhone,

        @NotBlank(message = "우편번호를 입력해 주세요.")
        @Pattern(regexp = "^[0-9]{5}$", message = "우편번호는 5자리 숫자로 입력해 주세요.")
        String zipCode,

        @NotBlank(message = "주소를 입력해 주세요.")
        @Size(max = 255, message = "주소는 255자 이하로 입력해 주세요.")
        String address,

        @Size(max = 255, message = "상세 주소는 255자 이하로 입력해 주세요.")
        String addressDetail,

        @Size(max = 255, message = "배송 요청 사항은 255자 이하로 입력해 주세요.")
        String deliveryMessage) {

    public DeliveryRequest {
        receiverName = trim(receiverName);
        receiverPhone = trim(receiverPhone);
        zipCode = trim(zipCode);
        address = trim(address);
        addressDetail = trim(addressDetail);
        deliveryMessage = trim(deliveryMessage);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
