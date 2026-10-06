package com.possaas.modules.table.controller;

import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.table.service.TableConflictTranslator;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = {TableAreaController.class, RestaurantTableController.class, TableSessionController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TableConflictHandler {
    private final Clock clock;

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class, jakarta.persistence.LockTimeoutException.class,
            jakarta.persistence.PessimisticLockException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        var error = TableConflictTranslator.translate(failure);
        return ResponseEntity.status(error.getStatus()).body(new ErrorResponse(false, error.getCode(),
                error.getMessage(), Map.of(), clock.instant()));
    }
}
