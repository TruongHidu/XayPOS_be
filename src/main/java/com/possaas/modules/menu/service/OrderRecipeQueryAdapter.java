package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.order.application.port.ActiveOrderRecipeQuery;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrderRecipeQueryAdapter implements ActiveOrderRecipeQuery {
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;
    private final ItemRepository items;

    @Override
    public Map<UUID, Boolean> findRecipeSettings(UUID restaurantId, Collection<UUID> menuItemIds) {
        if (menuItemIds.isEmpty()) return Map.of();
        return items.findAllByRestaurantIdAndIdIn(restaurantId, menuItemIds).stream()
                .filter(item -> item.getItemType() == ItemType.MENU_ITEM && item.getDeletedAt() == null)
                .collect(Collectors.toUnmodifiableMap(com.possaas.modules.menu.entity.Item::getId,
                        com.possaas.modules.menu.entity.Item::isRecipeEnabled));
    }

    @Override
    public Map<UUID, RecipeDefinition> findActive(UUID restaurantId, Collection<UUID> menuItemIds) {
        if (menuItemIds.isEmpty()) return Map.of();
        var active = recipes.findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(restaurantId, menuItemIds);
        if (active.isEmpty()) return Map.of();
        var details = recipeItems.findAllByRestaurantIdAndRecipeIdInOrderByRecipeIdAscIngredientIdAsc(
                restaurantId, active.stream().map(recipe -> recipe.getId()).toList());
        var ingredientIds = details.stream().map(RecipeItem::getIngredientId).distinct().toList();
        var current = ingredientIds.isEmpty() ? Map.<UUID, com.possaas.modules.menu.entity.Item>of()
                : items.findAllByRestaurantIdAndIdIn(restaurantId, ingredientIds).stream()
                        .collect(Collectors.toMap(item -> item.getId(), Function.identity()));
        Map<UUID, List<RecipeItem>> byRecipe = details.stream().collect(Collectors.groupingBy(RecipeItem::getRecipeId));
        Map<UUID, RecipeDefinition> result = new LinkedHashMap<>();
        for (var recipe : active) {
            var lines = byRecipe.getOrDefault(recipe.getId(), List.of());
            if (lines.isEmpty()) throw invalidRecipe();
            var definitions = lines.stream().map(line -> {
                var ingredient = current.get(line.getIngredientId());
                if (ingredient == null || ingredient.getItemType() != ItemType.INGREDIENT
                        || ingredient.getDeletedAt() != null || !ingredient.isActive())
                    throw new ConflictException("RECIPE_INGREDIENT_UNAVAILABLE", "Recipe ingredient is unavailable");
                if (!line.getIngredientUnitSnapshot().equals(ingredient.getBaseUnit()))
                    throw new ConflictException("RECIPE_INGREDIENT_UNIT_MISMATCH", "Recipe ingredient unit changed");
                return new IngredientDefinition(ingredient.getId(), line.getIngredientSkuSnapshot(),
                        line.getIngredientNameSnapshot(), line.getIngredientUnitSnapshot(), line.getQuantity(),
                        line.getWastePercent(), ingredient.isTrackInventory());
            }).toList();
            if (result.put(recipe.getMenuItemId(), new RecipeDefinition(recipe.getId(), recipe.getMenuItemId(),
                    recipe.getRecipeVersion(), recipe.getYieldQuantity(), recipe.getYieldUnit(), definitions)) != null)
                throw invalidRecipe();
        }
        return Map.copyOf(result);
    }

    private static ConflictException invalidRecipe() {
        return new ConflictException("INVALID_RECIPE_SNAPSHOT_DATA", "Active recipe data is invalid");
    }
}
