package com.possaas.modules.order.application.port;
import java.math.BigDecimal;
import java.util.*;
/** Live catalogue input for fresh acceptance only; no management permission is required internally. */
public interface OrderReferenceCostQuery {
    Prices findManualCosts(UUID tenant, Collection<UUID> menuItemIds);
    Prices findIngredientCosts(UUID tenant, Collection<UUID> ingredientIds);
    record Price(UUID itemId, String baseUnit, BigDecimal costPrice, boolean costConfigured) {}
    record Prices(String currencyCode, Map<UUID,Price> values) {
        public Prices { values=Map.copyOf(values); }
    }
}
