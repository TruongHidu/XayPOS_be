package com.possaas.modules.inventory.controller;

import static org.assertj.core.api.Assertions.*;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionSystemException;

class StockAuditConflictHandlerTest {
    @Test void onlyRecognizedConstraintsAreBusinessConflicts(){
        assertThat(StockAuditConflictHandler.translate(constraint("ux_stock_audit_counting")).getCode()).isEqualTo("WAREHOUSE_STOCK_AUDIT_IN_PROGRESS");
        assertThat(StockAuditConflictHandler.translate(constraint("uq_stock_audit_idempotency")).getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(StockAuditConflictHandler.translate(new OptimisticLockingFailureException("race")).getCode()).isEqualTo("CONCURRENT_STOCK_AUDIT_UPDATE");
        assertThat(StockAuditConflictHandler.translate(new TransactionSystemException("deferred",constraint("ck_stock_audit_commit"))).getCode()).isEqualTo("CONCURRENT_STOCK_AUDIT_UPDATE");
        assertThat(StockAuditConflictHandler.translate(constraint("unknown_fk"))).isNull();
    }
    RuntimeException constraint(String name){return new DataIntegrityViolationException("constraint",new ConstraintViolationException("constraint",new SQLException(),name));}
}
