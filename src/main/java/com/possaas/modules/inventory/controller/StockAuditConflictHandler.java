package com.possaas.modules.inventory.controller;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.GlobalExceptionHandler.ErrorResponse;
import com.possaas.modules.inventory.service.StockAuditPolicy;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=StockAuditController.class)
@Order(Ordered.HIGHEST_PRECEDENCE) @RequiredArgsConstructor
public class StockAuditConflictHandler {
    private final Clock clock;
    @ExceptionHandler({DataIntegrityViolationException.class,OptimisticLockingFailureException.class,
        PessimisticLockingFailureException.class,TransactionSystemException.class,JpaSystemException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException failure){
        ConflictException translated=translate(failure);
        // Unknown integrity errors remain server failures; never disguise them as an audit in progress.
        if(translated==null)return ResponseEntity.internalServerError().body(new ErrorResponse(false,"INTERNAL_ERROR","Unable to persist stock audit",Map.of(),clock.instant()));
        return ResponseEntity.status(translated.getStatus()).body(new ErrorResponse(false,translated.getCode(),translated.getMessage(),Map.of(),clock.instant()));
    }
    static ConflictException translate(Throwable failure){
        if(failure instanceof OptimisticLockingFailureException || failure instanceof PessimisticLockingFailureException)
            return concurrent();
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            if(cause instanceof ConstraintViolationException constraint){
                return switch(String.valueOf(constraint.getConstraintName())){
                    case "ux_stock_audit_counting" -> StockAuditPolicy.conflict("WAREHOUSE_STOCK_AUDIT_IN_PROGRESS","Warehouse already has a COUNTING audit");
                    case "uq_stock_audit_idempotency" -> StockAuditPolicy.conflict("IDEMPOTENCY_KEY_REUSED","Idempotency-Key was used concurrently");
                    case "uq_stock_audit_code","uq_stock_audit_item","ux_stock_document_stock_audit",
                         "ck_stock_audit_history","ck_stock_audit_item_history","ck_stock_audit_commit",
                         "ck_stock_audit_document_history","uq_inventory_transaction_document_item",
                         "uq_inventory_transaction_idempotency","uq_inventory_balance_warehouse_item" -> concurrent();
                    default -> null;
                };
            }
        }
        return null;
    }
    private static ConflictException concurrent(){return StockAuditPolicy.conflict("CONCURRENT_STOCK_AUDIT_UPDATE","Stock audit changed concurrently; reload before retrying");}
}
