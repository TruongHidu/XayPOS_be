package com.possaas.modules.qrorder.controller;

import com.possaas.common.exception.*;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.qrorder.service.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=PublicQrOrderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class PublicQrOrderErrorHandler {
    private final Clock clock;
    private final QrRateLimiter limiter;
    private static final Set<String> ALLOWED=Set.of("QR_GUEST_ACCESS_INVALID","QR_ORDER_UNAVAILABLE","QR_ORDER_REQUEST_NOT_FOUND",
            "QR_ORDER_REQUEST_EXPIRED","QR_ORDER_CHECKOUT_IN_PROGRESS","IDEMPOTENCY_KEY_REUSED","VALIDATION_ERROR",
            "ITEM_NOT_SELLABLE","ITEM_OUT_OF_STOCK","ORDER_AMOUNT_LIMIT_EXCEEDED");
    @ExceptionHandler(QrRateLimitException.class)
    ResponseEntity<ErrorResponse> rateLimit(QrRateLimitException failure) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                .header("Retry-After",Integer.toString(limiter.retryAfterSeconds())).body(envelope(failure.getCode(),failure.getMessage()));
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException failure) {
        if("QR_MENU_NOT_FOUND".equals(failure.getCode()) || "PUBLIC_MENU_NOT_FOUND".equals(failure.getCode()) || "MENU_ITEM_NOT_FOUND".equals(failure.getCode()))
            return error(HttpStatus.NOT_FOUND,"QR_ORDER_UNAVAILABLE","QR ordering is not available.");
        if(!ALLOWED.contains(failure.getCode())) return error(HttpStatus.FORBIDDEN,"QR_ORDER_UNAVAILABLE","QR ordering is not available.");
        return error(failure.getStatus(),failure.getCode(),failure.getMessage());
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> invalid(Exception failure) { return error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_BODY","Invalid QR request headers or body."); }
    @ExceptionHandler({DataIntegrityViolationException.class,OptimisticLockingFailureException.class,ConcurrencyFailureException.class})
    ResponseEntity<ErrorResponse> conflict(Exception failure) { return error(HttpStatus.CONFLICT,"CONCURRENT_QR_REQUEST_UPDATE","QR request changed concurrently."); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception failure) { return error(HttpStatus.INTERNAL_SERVER_ERROR,"INTERNAL_ERROR","Cannot process QR request. Please try again."); }
    private ErrorResponse envelope(String code,String message) { return new ErrorResponse(false,code,message,Map.of(),clock.instant()); }
    private ResponseEntity<ErrorResponse> error(HttpStatus status,String code,String message) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope(code,message)); }
}
