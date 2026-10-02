package com.possaas.modules.subscription.controller;

import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Includes conflicts raised when the service transaction commits. */
@RestControllerAdvice(assignableTypes = AdminPackageController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PackageConflictHandler {
    private final Clock clock;

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        String code = "CONCURRENT_PACKAGE_UPDATE";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                code = switch (String.valueOf(violation.getConstraintName())) {
                    case "packages_code_key" -> "PACKAGE_ALREADY_EXISTS";
                    case "features_code_key" -> "FEATURE_ALREADY_EXISTS";
                    case "package_features_pkey" -> "PACKAGE_FEATURE_ALREADY_EXISTS";
                    default -> code;
                };
            }
        }
        return ResponseEntity.status(409).body(new ErrorResponse(false, code,
            "Catalog update conflicts with the current data", Map.of(), clock.instant()));
    }
}
