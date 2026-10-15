package com.possaas.modules.qrorder.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = QrStaffRequestController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class QrStaffErrorHandler {
    private final Clock clock;
    @ExceptionHandler(BusinessException.class) ResponseEntity<ErrorResponse> business(BusinessException failure) {
        return error(failure.getStatus(), failure.getCode(), failure.getMessage());
    }
    @ExceptionHandler({BindException.class, ConstraintViolationException.class, MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> body(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Request body is missing, malformed or contains unsupported fields"); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(Exception ignored) { return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Required QR review permission is missing"); }
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class, PessimisticLockingFailureException.class,
        jakarta.persistence.LockTimeoutException.class, jakarta.persistence.PessimisticLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                if ("uq_qr_request_idempotency".equals(constraint.getConstraintName()))
                    return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Idempotency key conflicts with another request");
                if ("ux_order_serving_session".equals(constraint.getConstraintName()))
                    return error(HttpStatus.CONFLICT, "TABLE_SESSION_HAS_SERVING_ORDER", "Session already has a serving order");
            }
        }
        return error(HttpStatus.CONFLICT, "CONCURRENT_QR_REQUEST_UPDATE", "QR request changed concurrently or conflicts with a persistence constraint");
    }
    @ExceptionHandler(Exception.class) ResponseEntity<ErrorResponse> unexpected(Exception ignored) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Could not process the QR review request");
    }
    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new ErrorResponse(false, code, message, Map.of(), clock.instant()));
    }
}
