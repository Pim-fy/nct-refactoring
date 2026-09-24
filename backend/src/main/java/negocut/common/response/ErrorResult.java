package negocut.common.response;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)      // JSON으로 바꿀 때 값이 null인 필드는 아예 쓰지 않음.
public class ErrorResult {
    
    private final String code;
    private final String message;
    private final List<FieldError> details;

    private ErrorResult(String code, String message, List<FieldError> details){
        this.code = code;
        this.message = message;
        this.details = details;
    }

    public static ErrorResult of(String code, String message){
        return new ErrorResult(code, message, null);
    }

    public static ErrorResult of(String code, String message, List<FieldError> details){
        return new ErrorResult(code, message, details);
    }

    @Getter
    public static class FieldError{
        
        private final String field;
        private final String message;

        public FieldError(String field, String message){
            this.field = field;
            this.message = message;
        }
    }
}

