package com.possaas.modules.menu.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.dto.RecipeRequests;
import com.possaas.modules.menu.dto.RecipeResponse;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RecipeCommandService {
    private final RecipeAccessService access;
    private final RecipeRepository recipes;
    private final RecipeItemRepository recipeItems;
    private final RecipeValidationPolicy validation;
    private final RecipeLifecyclePolicy lifecycle;
    private final RecipeResponseMapper mapper;
    private final RecipeAudit audit;
    private final Clock clock;

    public RecipeResponse create(UUID menuItemId, RecipeRequests.Create request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item menuItem = access.menuItem(actor.restaurantId(), menuItemId);
        access.requireNoActiveRecipe(actor.restaurantId(), menuItemId);
        var validated = validation.validate(actor.restaurantId(), request.yieldQuantity(), request.ingredients());
        Instant now = clock.instant();
        Recipe recipe = new Recipe();
        recipe.initialize(actor.restaurantId(), menuItemId,
                recipes.findMaxRecipeVersion(actor.restaurantId(), menuItemId) + 1,
                validated.yieldQuantity(), menuItem.getBaseUnit(), now);
        recipes.saveAndFlush(recipe);
        List<RecipeItem> lines = createLines(actor.restaurantId(), recipe.getId(), validated, now);
        recipeItems.saveAllAndFlush(lines);
        audit.record(actor, "RECIPE_CREATED", recipe, null, audit.snapshot(recipe, lines), ipAddress);
        return mapper.detail(recipe, lines);
    }

    public RecipeResponse createVersion(UUID menuItemId, UUID activeRecipeId,
            RecipeRequests.CreateVersion request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item menuItem = access.menuItem(actor.restaurantId(), menuItemId);
        Recipe current = access.recipeForUpdate(actor.restaurantId(), menuItemId, activeRecipeId);
        lifecycle.requireActive(current);
        lifecycle.requireVersion(current, request.expectedVersion());
        List<RecipeItem> currentLines = recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(
                actor.restaurantId(), current.getId());
        var validated = validation.validate(actor.restaurantId(), request.yieldQuantity(), request.ingredients());
        lifecycle.requireChanged(current, currentLines, validated);

        Map<String, Object> before = audit.snapshot(current, currentLines);
        int nextVersion = recipes.findMaxRecipeVersion(actor.restaurantId(), menuItemId) + 1;
        Instant now = clock.instant();
        current.deactivate(now);
        recipes.saveAndFlush(current);

        Recipe replacement = new Recipe();
        replacement.initialize(actor.restaurantId(), menuItemId, nextVersion,
                validated.yieldQuantity(), menuItem.getBaseUnit(), now);
        recipes.saveAndFlush(replacement);
        List<RecipeItem> replacementLines = createLines(actor.restaurantId(), replacement.getId(), validated, now);
        recipeItems.saveAllAndFlush(replacementLines);
        audit.record(actor, "RECIPE_VERSION_CREATED", replacement, before,
                audit.snapshot(replacement, replacementLines), ipAddress);
        return mapper.detail(replacement, replacementLines);
    }

    public void deactivate(UUID menuItemId, UUID recipeId, long expectedVersion, String ipAddress) {
        CurrentUser actor = access.require(true);
        Item menuItem = access.menuItem(actor.restaurantId(), menuItemId);
        Recipe recipe = access.recipeForUpdate(actor.restaurantId(), menuItemId, recipeId);
        if (!recipe.isActive())
            return;
        if (menuItem.isRecipeEnabled())
            throw new com.possaas.common.exception.ConflictException("RECIPE_APPLICATION_ENABLED",
                    "Disable recipe application on the menu item before deactivating its recipe");
        lifecycle.requireVersion(recipe, expectedVersion);
        List<RecipeItem> lines = recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(
                actor.restaurantId(), recipe.getId());
        Map<String, Object> before = audit.snapshot(recipe, lines);
        recipe.deactivate(clock.instant());
        recipes.saveAndFlush(recipe);
        audit.record(actor, "RECIPE_DEACTIVATED", recipe, before, audit.snapshot(recipe, lines), ipAddress);
    }

    private List<RecipeItem> createLines(UUID restaurantId, UUID recipeId,
            RecipeValidationPolicy.ValidatedRecipe validated, Instant now) {
        return validated.ingredients().stream().map(validatedLine -> {
            RecipeItem line = new RecipeItem();
            line.initialize(restaurantId, recipeId, validatedLine.ingredient(),
                    validatedLine.quantity(), validatedLine.wastePercent(), now);
            return line;
        }).toList();
    }
}
