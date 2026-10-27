package com.possaas.modules.menu.service;

import com.possaas.common.exception.BusinessException;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** The numeric zero and an explicitly configured zero are different business facts. */
@Component
@RequiredArgsConstructor
public class ReferenceCostConfigurationPolicy {
    private final MenuItemValidationPolicy validation;

    public Value create(BigDecimal price, Boolean configured) {
        if (Boolean.TRUE.equals(configured) && price == null)
            throw invalid("COST_PRICE_REQUIRED", "An explicitly configured cost requires a price");
        return normalize(price, configured == null ? price != null : configured);
    }

    public Value update(BigDecimal currentPrice, boolean currentConfigured, BigDecimal price, Boolean configured) {
        if (price == null && configured == null) return new Value(currentPrice, currentConfigured);
        if (Boolean.FALSE.equals(configured)) return normalize(price, false);
        return normalize(price == null ? currentPrice : price, true);
    }

    private Value normalize(BigDecimal requested, boolean configured) {
        BigDecimal price = validation.price(requested == null ? BigDecimal.ZERO : requested);
        if (!configured && price.signum() != 0)
            throw invalid("INVALID_COST_CONFIGURATION", "An unconfigured reference cost cannot have a positive price");
        return new Value(price, configured);
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record Value(BigDecimal price, boolean configured) {}
}
