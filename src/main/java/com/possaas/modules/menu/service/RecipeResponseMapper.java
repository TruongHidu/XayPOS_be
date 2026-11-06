package com.possaas.modules.menu.service;

import com.possaas.modules.menu.dto.RecipeResponse;
import com.possaas.modules.menu.dto.RecipeSummaryResponse;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RecipeResponseMapper {
    public RecipeResponse detail(Recipe recipe, List<RecipeItem> items) {
        return new RecipeResponse(recipe.getId(), recipe.getMenuItemId(), recipe.getRecipeVersion(),
                recipe.getYieldQuantity(), recipe.getYieldUnit(), recipe.isActive(),
                items.stream().map(this::ingredient).toList(), recipe.getVersion(), recipe.getCreatedAt(),
                recipe.getUpdatedAt());
    }

    public RecipeSummaryResponse summary(Recipe recipe, long ingredientCount) {
        return new RecipeSummaryResponse(recipe.getId(), recipe.getMenuItemId(), recipe.getRecipeVersion(),
                recipe.getYieldQuantity(), recipe.getYieldUnit(), recipe.isActive(), ingredientCount,
                recipe.getVersion(), recipe.getCreatedAt(), recipe.getUpdatedAt());
    }

    private RecipeResponse.Ingredient ingredient(RecipeItem item) {
        return new RecipeResponse.Ingredient(item.getId(), item.getIngredientId(),
                item.getIngredientSkuSnapshot(), item.getIngredientNameSnapshot(), item.getIngredientUnitSnapshot(),
                item.getQuantity(), item.getWastePercent());
    }
}
