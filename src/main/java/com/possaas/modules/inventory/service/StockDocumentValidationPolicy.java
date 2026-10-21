package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class StockDocumentValidationPolicy {
    public BigDecimal quantity(BigDecimal value) {
        if (value == null || value.signum() <= 0) throw invalidQuantity();
        try { return value.setScale(3, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException failure) { throw invalidQuantity(); }
    }

    public BigDecimal cost(BigDecimal value) {
        if (value == null) return new BigDecimal("0.00");
        if (value.signum() < 0)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_COST", "unitCost cannot be negative");
        try { return value.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException failure) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_COST", "unitCost supports two decimals");
        }
    }

    public String note(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public String idempotencyKey(String value) {
        if (value == null || value.isBlank())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required");
        String trimmed = value.trim();
        if (trimmed.length() > 100)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Idempotency-Key is too long");
        return trimmed;
    }

    private static BusinessException invalidQuantity() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_QUANTITY",
                "quantity must be positive with at most three decimals");
    }
}
