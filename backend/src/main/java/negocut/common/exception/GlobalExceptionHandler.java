package negocut.common.exception;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;
import negocut.common.response.ApiResponse;
import negocut.common.response.ErrorResult;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(InputInvalidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInputInvalid(InputInvalidException ex){

        ErrorCode errorCode = ex.getErrorCode();
        log.warn("[InputInvalid] {}", errorCode.name());

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage(), ex.getDetails())));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex){

        ErrorCode errorCode = ex.getErrorCode();
        log.warn("[Business] {} - {}", errorCode.name(), ex.getMessage());

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex){

        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;

        List<ErrorResult.FieldError> details = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(e -> new ErrorResult.FieldError(e.getField(), e.getDefaultMessage()))
            .toList();

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage(), details)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex){

        ErrorCode errorCode = ErrorCode.ENDPOINT_NOT_FOUND;

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex){

        ErrorCode errorCode = ErrorCode.METHOD_NOT_ALLOWED;

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }

    // 본문이 JSON이 아니거나, 없는 enum 값이거나, 필수 파라미터가 없거나 타입이 맞지 않는 요청은 클라이언트의 잘못이므로 400이다.
    // (이 핸들러가 없으면 아래의 "그 밖의 모든 예외"로 떨어져 500이 된다.)
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex){

        ErrorCode errorCode = ErrorCode.INVALID_INPUT_VALUE;
        log.warn("[BadRequest] {}", ex.getClass().getSimpleName());

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex){

        ErrorCode errorCode = ErrorCode.UNSUPPORTED_MEDIA_TYPE;

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex){

        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        log.error("[Unexpected] ", ex);

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }



}
