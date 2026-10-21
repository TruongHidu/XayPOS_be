package com.possaas.modules.inventory.controller;

import com.possaas.common.exception.ConflictException;
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

@RestControllerAdvice(assignableTypes = StockDocumentController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class StockDocumentConflictHandler {
    private final Clock clock;

    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure) {
        ConflictException translated = translate(failure);
        return ResponseEntity.status(translated.getStatus()).body(new ErrorResponse(false, translated.getCode(),
                translated.getMessage(), Map.of(), clock.instant()));
    }

    private static ConflictException translate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) {
                return switch (String.valueOf(constraint.getConstraintName())) {
                    case "uq_stock_document_idempotency" -> new ConflictException("IDEMPOTENCY_KEY_REUSED",
                            "Idempotency-Key was used concurrently");
                    case "uq_stock_document_item" -> new ConflictException("DUPLICATE_STOCK_DOCUMENT_ITEM",
                            "Ingredient already exists in document");
                    case "uq_stock_document_code" -> new ConflictException("STOCK_DOCUMENT_CODE_EXISTS",
                            "Stock document code already exists");
                    case "uq_inventory_transaction_document_item", "uq_inventory_transaction_idempotency" ->
                            new ConflictException("CONCURRENT_STOCK_DOCUMENT_UPDATE",
                                    "Stock document was already posted");
                    case "uq_inventory_balance_warehouse_item" -> new ConflictException(
                            "CONCURRENT_INVENTORY_UPDATE", "Inventory changed concurrently");
                    default -> concurrentDocument();
                };
            }
        }
        return concurrentDocument();
    }

    private static ConflictException concurrentDocument() {
        return new ConflictException("CONCURRENT_STOCK_DOCUMENT_UPDATE", "Stock document changed concurrently");
    }
}
