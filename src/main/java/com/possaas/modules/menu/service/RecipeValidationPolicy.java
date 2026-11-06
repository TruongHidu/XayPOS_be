package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.dto.RecipeRequests;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecipeValidationPolicy {
    private static final BigDecimal ZERO_WASTE = new BigDecimal("0.00");
    private final ItemRepository items;

    public ValidatedRecipe validate(UUID restaurantId, BigDecimal yieldQuantity,
            List<RecipeRequests.Ingredient> requested) {
        if (requested == null || requested.isEmpty())
            throw new ConflictException("RECIPE_INGREDIENTS_REQUIRED", "Recipe requires at least one ingredient");
        if (requested.size() > 100)
            throw new ConflictException("TOO_MANY_RECIPE_INGREDIENTS", "Recipe supports at most 100 ingredients");

        var ids = new HashSet<UUID>();
        for (var line : requested)
            if (!ids.add(line.ingredientId()))
                throw new ConflictException("DUPLICATE_RECIPE_INGREDIENT",
                        "An ingredient may appear only once in a recipe");

        Map<UUID, Item> found = new HashMap<>();
        items.findAllByRestaurantIdAndIdIn(restaurantId, ids).forEach(item -> found.put(item.getId(), item));
        List<ValidatedIngredient> lines = new ArrayList<>(requested.size());
        for (var request : requested) {
            Item ingredient = found.get(request.ingredientId());
            if (ingredient == null || ingredient.getItemType() != ItemType.INGREDIENT
                    || ingredient.getDeletedAt() != null)
                throw new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found");
            if (!ingredient.isActive())
                throw new ConflictException("INGREDIENT_INACTIVE", "Inactive ingredients cannot be added to recipes");
            BigDecimal waste = request.wastePercent() == null ? ZERO_WASTE : request.wastePercent();
            lines.add(new ValidatedIngredient(ingredient, normalize(request.quantity(), 3), normalize(waste, 2)));
        }
        lines.sort((left, right) -> left.ingredient().getId().compareTo(right.ingredient().getId()));
        return new ValidatedRecipe(normalize(yieldQuantity, 3), List.copyOf(lines));
    }

    private BigDecimal normalize(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public record ValidatedRecipe(BigDecimal yieldQuantity, List<ValidatedIngredient> ingredients) {
    }

    public record ValidatedIngredient(Item ingredient, BigDecimal quantity, BigDecimal wastePercent) {
    }
}
