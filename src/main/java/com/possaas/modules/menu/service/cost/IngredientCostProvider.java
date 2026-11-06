package com.possaas.modules.menu.service.cost;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Prices are expressed per current base unit, not per recipe batch or sales unit. */
public interface IngredientCostProvider {
    String costSource();

    Map<UUID, IngredientCost> findCosts(UUID restaurantId, Collection<UUID> ingredientIds);

    record IngredientCost(UUID ingredientId, String baseUnit, BigDecimal unitCost, boolean configured) {
        public IngredientCost(UUID ingredientId, String baseUnit, BigDecimal unitCost) {
            this(ingredientId, baseUnit, unitCost, true);
        }
    }
}
