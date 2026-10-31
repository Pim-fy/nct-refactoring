package negocut.common.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public enum ErrorCode {
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않는 요청 방식입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    REQUEST_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "요청 본문이 너무 큽니다."),

    // 중복 요청
    IDEMPOTENCY_PROCESSING(HttpStatus.CONFLICT, "같은 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요."),

    // 회원
    MEMBER_INPUT_INVALID(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    MEMBER_ID_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 아이디입니다."),
    MEMBER_NICKNAME_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 닉네임입니다."),
    MEMBER_EMAIL_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 이메일입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    MEMBER_ALREADY_WITHDRAWN(HttpStatus.CONFLICT, "탈퇴한 회원입니다."),
    MEMBER_ALREADY_RESTRICTED(HttpStatus.CONFLICT, "이미 이용이 제한된 회원입니다. 현재 제한을 확인한 뒤 교체해 주세요."),
    MEMBER_NOT_RESTRICTED(HttpStatus.CONFLICT, "이용이 제한된 회원이 아닙니다."),
    MEMBER_RESTRICTION_CHANGED(HttpStatus.CONFLICT, "확인하신 뒤 이용 제한이 바뀌었습니다. 현재 제한을 다시 확인해 주세요."),

    // 카테고리
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."),
    CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 카테고리 이름입니다."),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "사용 중인 카테고리는 삭제할 수 없습니다. 사용 여부만 꺼 주세요."),

    // 경매
    AUCTION_INPUT_INVALID(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUCTION_END_TIME_INVALID(HttpStatus.BAD_REQUEST, "종료 시각은 지금부터 10분 뒤 이후, 30일 이내여야 합니다."),
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "경매를 찾을 수 없습니다."),
    AUCTION_STATUS_INVALID(HttpStatus.CONFLICT, "중단할 수 없는 경매입니다. 등록 또는 진행 중인 경매만 중단할 수 있습니다."),
    AUCTION_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "상품 이미지를 한 장 이상 올려 주세요."),

    // 거래
    TRADE_NOT_FOUND(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다."),
    TRADE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 거래에 대한 권한이 없습니다."),
    TRADE_STATUS_INVALID(HttpStatus.CONFLICT, "현재 거래 상태에서는 할 수 없는 처리입니다."),
    TRADE_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 거래입니다."),
    TRADE_CANCEL_ALREADY_REQUESTED(HttpStatus.CONFLICT, "처리 중인 취소 요청이 있습니다."),
    TRADE_CANCEL_REQUEST_CLOSED(HttpStatus.CONFLICT, "이미 닫힌 취소 요청입니다."),
    TRADE_CANCEL_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "취소 요청을 찾을 수 없습니다."),
    TRADE_CANCEL_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 거래에서 낼 수 있는 취소 요청 횟수(3회)를 넘었습니다."),
    DISPUTE_ALREADY_OPEN(HttpStatus.CONFLICT, "처리 중인 분쟁이 있습니다."),
    DISPUTE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "이 거래에서 접수할 수 있는 분쟁 횟수(3회)를 넘었습니다."),

    // 신고
    REPORT_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "신고할 대상을 찾을 수 없습니다."),
    REPORT_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 접수한 신고가 처리 중입니다."),
    REPORT_SELF_NOT_ALLOWED(HttpStatus.FORBIDDEN, "본인의 경매나 본인 자신은 신고할 수 없습니다."),

    // 입찰
    AUCTION_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 경매가 아닙니다."),
    BID_SELF_AUCTION_NOT_ALLOWED(HttpStatus.FORBIDDEN, "본인이 등록한 경매에는 입찰할 수 없습니다."),
    BID_AMOUNT_TOO_LOW(HttpStatus.BAD_REQUEST, "입찰 금액이 최소 입찰 금액보다 낮습니다."),
    BID_UNIT_MISMATCH(HttpStatus.BAD_REQUEST, "입찰 금액은 입찰 단위의 배수여야 합니다."),

    // 포인트
    POINT_NOT_ENOUGH(HttpStatus.CONFLICT, "사용 가능한 포인트가 부족합니다."),
    POINT_CHARGE_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "충전 금액은 1,000 포인트 단위로 1,000 이상 100만 이하여야 합니다."),
    POINT_BALANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "충전 후 보유 포인트가 1,000만 포인트를 넘을 수 없습니다."),

    // 파일
    FILE_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "JPEG, PNG, WebP 이미지만 올릴 수 있습니다."),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "이미지는 5MB 이하만 올릴 수 있습니다."),
    FILE_COUNT_EXCEEDED(HttpStatus.CONFLICT, "올릴 수 있는 이미지 수를 넘었습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "이미지를 찾을 수 없습니다."),
    FILE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "이 이미지에 대한 권한이 없습니다."),
    FILE_IN_USE(HttpStatus.CONFLICT, "경매에 쓰인 이미지는 삭제할 수 없습니다."),

    // 로그인·토큰
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    MEMBER_STATUS_NOT_ALLOWED(HttpStatus.FORBIDDEN, "이용할 수 없는 계정입니다."),
    MEMBER_TRADE_RESTRICTED(HttpStatus.FORBIDDEN, "이용이 제한된 계정입니다. 경매 등록, 입찰, 포인트 충전을 할 수 없습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),

    // 이메일 인증
    VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.CONFLICT, "인증번호가 만료되었습니다. 다시 발송해 주세요."),
    VERIFICATION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "이메일 인증 정보가 올바르지 않거나 만료되었습니다."),
    EMAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "인증 메일을 보내지 못했습니다. 잠시 후 다시 발송해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message){
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
