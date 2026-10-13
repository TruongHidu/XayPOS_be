package com.possaas.modules.payment.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PaymentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class PaymentConflictHandler {
    private final Clock clock;

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException failure) {
        return error(failure.getStatus(), failure.getCode(), failure.getMessage());
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception failure) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> body(Exception failure) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Request body is missing or malformed");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(Exception failure) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Required payment permission is missing");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class, jakarta.persistence.LockTimeoutException.class,
        jakarta.persistence.PessimisticLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                if ("uq_payment_idempotency".equals(constraint.getConstraintName()))
                    return error(HttpStatus.CONFLICT, "PAYMENT_IDEMPOTENCY_KEY_REUSED", "Payment key conflict; reload before retrying");
                if ("uq_payment_code".equals(constraint.getConstraintName()))
                    return error(HttpStatus.CONFLICT, "PAYMENT_CODE_CONFLICT", "Payment code conflict; retry request");
                if ("ux_payment_provider_transaction".equals(constraint.getConstraintName()))
                    return error(HttpStatus.CONFLICT, "PROVIDER_TRANSACTION_ALREADY_RECORDED", "Provider transaction already recorded");
            }
        }
        return error(HttpStatus.CONFLICT, "CONCURRENT_PAYMENT_UPDATE", "Payment changed concurrently");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception failure) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Could not process payment request");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body(new ErrorResponse(false, code, message, Map.of(), clock.instant()));
    }
}
