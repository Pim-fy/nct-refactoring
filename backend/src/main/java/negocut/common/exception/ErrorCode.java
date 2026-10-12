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

    // 카테고리
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "카테고리를 찾을 수 없습니다."),
    CATEGORY_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 카테고리 이름입니다."),
    CATEGORY_IN_USE(HttpStatus.CONFLICT, "사용 중인 카테고리는 삭제할 수 없습니다. 사용 여부만 꺼 주세요."),

    // 경매
    AUCTION_INPUT_INVALID(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    AUCTION_END_TIME_INVALID(HttpStatus.BAD_REQUEST, "종료 시각은 지금부터 10분 뒤 이후, 30일 이내여야 합니다."),
    AUCTION_NOT_FOUND(HttpStatus.NOT_FOUND, "경매를 찾을 수 없습니다."),
    AUCTION_IMAGE_REQUIRED(HttpStatus.BAD_REQUEST, "상품 이미지를 한 장 이상 올려 주세요."),

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
