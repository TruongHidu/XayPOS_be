package com.possaas.modules.inventory.controller;

import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.inventory.service.LowStockAlertPersistence;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=LowStockAlertController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class LowStockAlertConflictHandler {
    private final Clock clock;
    @ExceptionHandler({OptimisticLockingFailureException.class,PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        var error=LowStockAlertPersistence.concurrent();
        return ResponseEntity.status(error.getStatus())
            .body(new ErrorResponse(false,error.getCode(),error.getMessage(),Map.of(),clock.instant()));
    }
}
