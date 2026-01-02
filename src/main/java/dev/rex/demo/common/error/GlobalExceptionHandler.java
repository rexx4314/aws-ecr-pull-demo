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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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
