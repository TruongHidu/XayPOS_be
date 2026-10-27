package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.dto.RecipeRequests;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Applying a definition is independent of editing or archiving its versions. */
@Component
@RequiredArgsConstructor
public class RecipeApplicationPolicy {
    private final FeatureAccessChecker features;
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;
    private final RecipeValidationPolicy validation;

    public void requireNewDisabled(UUID tenant, Boolean enabled) {
        if (!Boolean.TRUE.equals(enabled)) return;
        features.requireFeature(tenant, "RECIPE_MANAGEMENT");
        throw required();
    }

    public void requireCanEnable(Item item) {
        features.requireFeature(item.getRestaurantId(), "RECIPE_MANAGEMENT");
        var recipe = recipes.findByRestaurantIdAndMenuItemIdAndActiveTrue(item.getRestaurantId(), item.getId())
                .orElseThrow(RecipeApplicationPolicy::required);
        if (recipe.getYieldQuantity() == null || recipe.getYieldQuantity().signum() <= 0
                || !item.getBaseUnit().equals(recipe.getYieldUnit()))
            throw new ConflictException("RECIPE_YIELD_UNIT_MISMATCH", "Active recipe yield differs from the menu item unit");
        var lines = recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(item.getRestaurantId(), recipe.getId());
        var checked = validation.validate(item.getRestaurantId(), recipe.getYieldQuantity(), lines.stream()
                .map(line -> new RecipeRequests.Ingredient(line.getIngredientId(), line.getQuantity(), line.getWastePercent())).toList());
        var ingredients = checked.ingredients().stream().collect(Collectors.toMap(value -> value.ingredient().getId(),
                Function.identity()));
        if (lines.stream().anyMatch(line -> !line.getIngredientUnitSnapshot()
                .equals(ingredients.get(line.getIngredientId()).ingredient().getBaseUnit())))
            throw new ConflictException("RECIPE_INGREDIENT_UNIT_MISMATCH", "Recipe ingredient unit changed");
    }

    public static ConflictException required() {
        return new ConflictException("ACTIVE_RECIPE_REQUIRED", "Create a valid active recipe before enabling its application");
    }
}
