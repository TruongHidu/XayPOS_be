package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/** Reference cost per base unit, rounded only once to the existing cost_price scale. */
@Component
public class WeightedAverageCostPolicy {
    public BigDecimal calculate(BigDecimal previousQuantity, BigDecimal previousCost,
            BigDecimal receivedQuantity, BigDecimal receiptUnitCost) {
        if (previousQuantity == null || previousQuantity.signum() < 0
                || previousCost == null || previousCost.signum() < 0
                || receivedQuantity == null || receivedQuantity.signum() <= 0
                || receiptUnitCost == null || receiptUnitCost.signum() < 0)
            throw new ConflictException("INVALID_INVENTORY_COST_DATA",
                    "Reference costs and stock must be non-negative and receipt quantity must be positive");
        return previousQuantity.multiply(previousCost).add(receivedQuantity.multiply(receiptUnitCost))
                .divide(previousQuantity.add(receivedQuantity), 2, RoundingMode.HALF_UP);
    }
}
