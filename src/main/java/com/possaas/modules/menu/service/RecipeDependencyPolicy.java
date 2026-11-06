package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.application.port.ActiveRecipeUsageQuery;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecipeDependencyPolicy {
    private final ActiveRecipeUsageQuery usage;

    public void requireIngredientNotUsed(UUID restaurantId, UUID ingredientId) {
        if (usage.isIngredientUsedByActiveRecipe(restaurantId, ingredientId))
            throw new ConflictException("INGREDIENT_USED_BY_ACTIVE_RECIPE",
                    "Ingredient is used by an active recipe");
    }

    public void requireIngredientUnitChangeAllowed(UUID restaurantId, UUID ingredientId) {
        if (usage.isIngredientUsedByActiveRecipe(restaurantId, ingredientId))
            throw new ConflictException("INGREDIENT_UNIT_IN_USE",
                    "Ingredient unit cannot change while an active recipe uses it");
    }

    public void requireMenuItemUnitChangeAllowed(UUID restaurantId, UUID menuItemId) {
        if (usage.hasActiveRecipe(restaurantId, menuItemId))
            throw new ConflictException("MENU_ITEM_UNIT_IN_USE",
                    "Menu item unit cannot change while it has an active recipe");
    }

    public void requireMenuItemDeleteAllowed(UUID restaurantId, UUID menuItemId) {
        if (usage.hasActiveRecipe(restaurantId, menuItemId))
            throw new ConflictException("MENU_ITEM_HAS_ACTIVE_RECIPE",
                    "Deactivate the active recipe before deleting the menu item");
    }
}
