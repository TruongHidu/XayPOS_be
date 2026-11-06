package com.possaas.modules.menu.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.restaurant.controller.RestaurantMenuLinkController;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
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

@RestControllerAdvice(assignableTypes = {PublicRestaurantMenuController.class, RestaurantMenuLinkController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PublicRestaurantMenuErrorHandler {
    private static final Set<String> CODES = Set.of("PUBLIC_MENU_NOT_FOUND", "PUBLIC_MENU_UNAVAILABLE",
            "PUBLIC_MENU_GROUP_NOT_FOUND", "VALIDATION_ERROR", "PUBLIC_MENU_LINK_NOT_INITIALIZED",
            "CONCURRENT_MENU_LINK_UPDATE", "PUBLIC_MENU_TOKEN_CONFLICT", "RESTAURANT_NOT_FOUND", "TENANT_ACCESS_DENIED");
    private final Clock clock;

    // New contract uses details; the existing table QR error JSON is left untouched.
    public record ErrorResponse(boolean success, String code, String message, Map<String, String> details, Instant timestamp) {}

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException failure) {
        if (!CODES.contains(failure.getCode())) return internal();
        return error(failure.getStatus(), failure.getCode(), failure.getMessage());
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception failure) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Điều kiện yêu cầu không hợp lệ.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> body(HttpMessageNotReadableException failure) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY", "Dữ liệu yêu cầu không hợp lệ.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> forbidden(AccessDeniedException failure) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to manage this menu link");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> constraint(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "restaurants_public_order_token_key".equals(constraint.getConstraintName()))
                return error(HttpStatus.CONFLICT, "PUBLIC_MENU_TOKEN_CONFLICT", "Menu token conflict; retry");
        }
        return internal();
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class,
            jakarta.persistence.LockTimeoutException.class, jakarta.persistence.PessimisticLockException.class})
    ResponseEntity<ErrorResponse> concurrent(RuntimeException failure) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_MENU_LINK_UPDATE", "Menu link changed concurrently; retry");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception failure) { return internal(); }

    private ResponseEntity<ErrorResponse> internal() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Không thể xử lý yêu cầu. Vui lòng thử lại sau.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(false, code, message, Map.of(), clock.instant()));
    }
}
