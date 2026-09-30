package negocut.common.exception;

import java.util.List;

import org.springframework.validation.BindingResult;

import lombok.Getter;
import negocut.common.response.ErrorResult;

// 입력값 검증 실패. 필드별 사유(details)를 함께 응답한다. (API 명세서 0-3)
@Getter
public class InputInvalidException extends BusinessException {

    private final List<ErrorResult.FieldError> details;

    public InputInvalidException(ErrorCode errorCode, List<ErrorResult.FieldError> details) {
        super(errorCode);
        this.details = details;
    }

    // @Valid 검증 결과에 오류가 있으면 필드별 사유를 담아 던진다. 오류가 없으면 아무 일도 하지 않는다.
    public static void throwIfInvalid(BindingResult bindingResult, ErrorCode errorCode) {
        if (!bindingResult.hasErrors()) {
            return;
        }
        List<ErrorResult.FieldError> details = bindingResult.getFieldErrors().stream()
                .map(e -> new ErrorResult.FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        throw new InputInvalidException(errorCode, details);
    }
}
