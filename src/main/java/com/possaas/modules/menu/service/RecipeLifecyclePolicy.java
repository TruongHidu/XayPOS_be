package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RecipeLifecyclePolicy {
    public void requireVersion(Recipe recipe, long expectedVersion) {
        if (recipe.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_RECIPE_UPDATE",
                    "Recipe changed; reload before retrying");
    }

    public void requireActive(Recipe recipe) {
        if (!recipe.isActive())
            throw new ConflictException("RECIPE_NOT_ACTIVE", "Only the active recipe can create a new version");
    }

    public void requireChanged(Recipe recipe, List<RecipeItem> current,
            RecipeValidationPolicy.ValidatedRecipe replacement) {
        if (recipe.getYieldQuantity().compareTo(replacement.yieldQuantity()) != 0
                || current.size() != replacement.ingredients().size())
            return;
        Map<UUID, RecipeItem> currentByIngredient = new HashMap<>();
        current.forEach(line -> currentByIngredient.put(line.getIngredientId(), line));
        for (var newLine : replacement.ingredients()) {
            RecipeItem oldLine = currentByIngredient.get(newLine.ingredient().getId());
            if (oldLine == null || oldLine.getQuantity().compareTo(newLine.quantity()) != 0
                    || oldLine.getWastePercent().compareTo(newLine.wastePercent()) != 0)
                return;
        }
        throw new ConflictException("SAME_RECIPE_VERSION_NOT_ALLOWED",
                "The replacement recipe is identical to the active recipe");
    }
}
