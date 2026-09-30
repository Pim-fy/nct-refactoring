package negocut.common.exception;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex){

        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        log.error("[Unexpected] ", ex);

        return ResponseEntity
            .status(errorCode.getHttpStatus())
            .body(ApiResponse.error(ErrorResult.of(errorCode.name(), errorCode.getMessage())));
    }



}
