package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InventoryValidationPolicy {
    public String code(String value) {
        return required(value, 50).toUpperCase(Locale.ROOT);
    }

    public String name(String value) {
        return required(value, 100);
    }

    public BigDecimal threshold(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 3 || value.precision() - value.scale() > 11)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid minQuantity");
        return value.setScale(3);
    }

    private static String required(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid text value");
        return normalized;
    }
}
