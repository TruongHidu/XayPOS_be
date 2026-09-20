package com.possaas.modules.subscription.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.subscription.service.SubscriptionConflictTranslator;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Also handles conflicts raised at transaction commit, outside the service method body. */
@RestControllerAdvice(assignableTypes = AdminSubscriptionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SubscriptionConflictHandler {
    private final Clock clock;

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        BusinessException error = SubscriptionConflictTranslator.translate(failure);
        return ResponseEntity.status(error.getStatus()).body(new ErrorResponse(
            false, error.getCode(), error.getMessage(), Map.of(), clock.instant()
        ));
    }
}
