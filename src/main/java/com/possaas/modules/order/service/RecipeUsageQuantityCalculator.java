package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class RecipeUsageQuantityCalculator {
    private static final BigDecimal MAX = new BigDecimal("99999999999.999");

    public BigDecimal calculate(BigDecimal quantity, BigDecimal waste, BigDecimal ordered, BigDecimal yield) {
        if (!positive(quantity) || !positive(ordered) || !positive(yield) || waste == null
                || waste.signum() < 0 || waste.compareTo(new BigDecimal("100")) > 0)
            throw new ConflictException("INVALID_RECIPE_SNAPSHOT_DATA", "Recipe quantities or waste are invalid");
        BigDecimal required = quantity.multiply(BigDecimal.ONE.add(waste.movePointLeft(2)))
                .multiply(ordered).divide(yield, 3, RoundingMode.CEILING);
        if (required.compareTo(MAX) > 0)
            throw new ConflictException("RECIPE_USAGE_QUANTITY_OUT_OF_RANGE", "Recipe usage exceeds inventory quantity precision");
        return required;
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
