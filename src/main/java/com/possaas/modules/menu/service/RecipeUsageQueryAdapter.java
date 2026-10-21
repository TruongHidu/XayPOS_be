package com.possaas.modules.menu.service;

import com.possaas.modules.menu.application.port.ActiveRecipeUsageQuery;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecipeUsageQueryAdapter implements ActiveRecipeUsageQuery {
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;

    @Override
    public boolean isIngredientUsedByActiveRecipe(UUID restaurantId, UUID ingredientId) {
        return recipeItems.existsActiveRecipeUsingIngredient(restaurantId, ingredientId);
    }

    @Override
    public boolean hasActiveRecipe(UUID restaurantId, UUID menuItemId) {
        return recipes.existsByRestaurantIdAndMenuItemIdAndActiveTrue(restaurantId, menuItemId);
    }
}
