package com.possaas.modules.inventory.controller;

import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.inventory.service.InventoryConflictTranslator;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = WarehouseController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class WarehouseConflictHandler {
    private final Clock clock;

    @ExceptionHandler({ DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class })
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        var translated = InventoryConflictTranslator.translate(failure);
        var error = "CONCURRENT_INVENTORY_UPDATE".equals(translated.getCode())
                ? InventoryConflictTranslator.warehouseConflict()
                : translated;
        return ResponseEntity.status(error.getStatus())
                .body(new ErrorResponse(false, error.getCode(), error.getMessage(), Map.of(), clock.instant()));
    }
}
