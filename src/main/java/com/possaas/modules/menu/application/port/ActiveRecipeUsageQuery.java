package com.possaas.modules.menu.application.port;

import java.util.UUID;

public interface ActiveRecipeUsageQuery {
    boolean isIngredientUsedByActiveRecipe(UUID restaurantId, UUID ingredientId);

    boolean hasActiveRecipe(UUID restaurantId, UUID menuItemId);
}
