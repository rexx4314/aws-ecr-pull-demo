package dev.rex.demo.common.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전역 예외 처리기
 * <p>
 * 컨트롤러에서 발생한 예외를 가로채서 표준화된 ApiErrorResponse로 변환
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * ApiException 처리기
     * <p>
     * 로그를 남기고 에러 코드와 메시지를 포함한 bad request 응답을 반환
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApi(ApiException e, HttpServletRequest req) {
        log.warn("API error. code={}, msg={}, path={}", e.code(), e.getMessage(), req.getRequestURI());

        ApiErrorResponse body = new ApiErrorResponse(
                e.code().name(),
                e.getMessage(),
                (e.details() == null) ? Map.of() : e.details(),
                Instant.now(),
                req.getRequestURI()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Bean Validation 실패 처리기
     * <p>
     * 필드별 오류를 수집하여 details.fields에 포함한 bad request 응답을 반환
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();

        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            String field = fe.getField();
            String msg = (fe.getDefaultMessage() == null) ? "invalid" : fe.getDefaultMessage();
            fields.putIfAbsent(field, msg);
        }

        details.put("fields", fields);

        ApiErrorResponse body = new ApiErrorResponse(
                ErrorCode.INVALID_REQUEST.name(),
                "Validation failed",
                details,
                Instant.now(),
                req.getRequestURI()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * IllegalArgumentException 처리기
     * <p>
     * 잘못된 요청 파라미터 등으로 발생한 예외를 bad request로 변환
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException e, HttpServletRequest req) {
        ApiErrorResponse body = new ApiErrorResponse(
                ErrorCode.INVALID_REQUEST.name(),
                e.getMessage(),
                Map.of(),
                Instant.now(),
                req.getRequestURI()
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 그 외 모든 예외 처리기
     * <p>
     * 서버 내부 오류로 간주하여 internal server error 응답을 반환하고 상세 로그를 남김
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleAny(Exception e, HttpServletRequest req) {
        log.error("Unhandled error. path={}, err={}", req.getRequestURI(), e.getMessage(), e);

        ApiErrorResponse body = new ApiErrorResponse(
                ErrorCode.INTERNAL_ERROR.name(),
                "Internal error",
                Map.of(),
                Instant.now(),
                req.getRequestURI()
        );
        return ResponseEntity.internalServerError().body(body);
    }
}
