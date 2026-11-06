package com.possaas.modules.menu.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PublicQrMenuController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PublicQrMenuErrorHandler {
    private static final Set<String> PUBLIC_CODES = Set.of("QR_MENU_NOT_FOUND", "QR_MENU_UNAVAILABLE",
            "PUBLIC_MENU_GROUP_NOT_FOUND", "VALIDATION_ERROR");
    private final Clock clock;

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException failure) {
        if (!PUBLIC_CODES.contains(failure.getCode())) return internal();
        return error(failure.getStatus(), failure.getCode(), failure.getMessage());
    }

    @ExceptionHandler({BindException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> validation(Exception failure) {
        // Do not echo rejected values, which can themselves contain a QR token.
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Điều kiện tìm kiếm không hợp lệ.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception failure) {
        return internal();
    }

    private ResponseEntity<ErrorResponse> internal() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Không thể tải menu. Vui lòng thử lại sau.");
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ErrorResponse(false, code, message, Map.of(), clock.instant()));
    }
}
