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

    // 중복 요청
    IDEMPOTENCY_PROCESSING(HttpStatus.CONFLICT, "같은 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요."),

    // 회원
    MEMBER_INPUT_INVALID(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    MEMBER_ID_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 아이디입니다."),
    MEMBER_NICKNAME_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 닉네임입니다."),
    MEMBER_EMAIL_DUPLICATED(HttpStatus.BAD_REQUEST, "이미 사용 중인 이메일입니다."),

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
