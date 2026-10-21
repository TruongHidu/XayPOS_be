package com.possaas.modules.order.application.port;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal MENU contract: no caller recipe-management permission and no JPA entities. */
public interface ActiveOrderRecipeQuery {
    Map<UUID, RecipeDefinition> findActive(UUID restaurantId, Collection<UUID> menuItemIds);

    record RecipeDefinition(UUID id, UUID menuItemId, int version, BigDecimal yieldQuantity,
            String yieldUnit, List<IngredientDefinition> ingredients) {
        public RecipeDefinition { ingredients = List.copyOf(ingredients); }
    }

    record IngredientDefinition(UUID id, String sku, String name, String unit,
            BigDecimal recipeQuantity, BigDecimal wastePercent, boolean trackInventory) {}
}
