package com.possaas.modules.order.controller;

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

@RestControllerAdvice(assignableTypes={OrderQueryController.class,OrderCommandController.class,OrderSessionQueryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class OrderConflictHandler {
    private final Clock clock;
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException failure) { return error(failure.getStatus(),failure.getCode(),failure.getMessage()); }
    @ExceptionHandler({BindException.class,ConstraintViolationException.class,MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception failure) { return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Request validation failed"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> body(Exception failure) { return error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_BODY","Request body is missing, malformed or contains unsupported fields"); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(Exception failure) { return error(HttpStatus.FORBIDDEN,"FORBIDDEN","Required order permission is missing"); }
    @ExceptionHandler({DataIntegrityViolationException.class,OptimisticLockingFailureException.class,PessimisticLockingFailureException.class,
            jakarta.persistence.LockTimeoutException.class,jakarta.persistence.PessimisticLockException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                if("ux_order_serving_session".equals(constraint.getConstraintName())) return error(HttpStatus.CONFLICT,"TABLE_SESSION_HAS_SERVING_ORDER","Session already has a serving order; append to the existing order");
                if("ux_table_session_open".equals(constraint.getConstraintName())) return error(HttpStatus.CONFLICT,"TABLE_ALREADY_OCCUPIED","Table already has an open session; reload before retrying");
                if("uq_order_idempotency".equals(constraint.getConstraintName()) || "uq_order_item_submission_key".equals(constraint.getConstraintName())) return error(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED","Idempotency key conflict; reload before retrying");
                if("uq_order_code".equals(constraint.getConstraintName())) return error(HttpStatus.CONFLICT,"ORDER_CODE_CONFLICT","Order code conflict; retry with a new request");
            }
        }
        return error(HttpStatus.CONFLICT,"CONCURRENT_ORDER_UPDATE","Order changed concurrently or conflicts with a persistence constraint");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception failure) { return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Could not process the order request"); }
    private ResponseEntity<ErrorResponse> error(HttpStatus status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ErrorResponse(false,code,message,Map.of(),clock.instant()));
    }
}
