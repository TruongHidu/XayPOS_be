package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.dto.RecipeCostEstimateResponse;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.service.cost.IngredientCostProvider;
import com.possaas.modules.menu.service.cost.RecipeCostCalculationException;
import com.possaas.modules.menu.service.cost.RecipeCostCalculator;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecipeCostEstimateQueryService {
    private final RecipeAccessService access;
    private final RecipeItemRepository recipeItems;
    private final IngredientCostProvider costs;
    private final RecipeCostCalculator calculator;
    private final RestaurantRepository restaurants;
    private final Clock clock;

    public RecipeCostEstimateResponse estimate(UUID menuItemId, UUID recipeId) {
        var actor = access.require(false);
        access.menuItem(actor.restaurantId(), menuItemId);
        var recipe = access.recipe(actor.restaurantId(), menuItemId, recipeId);
        var lines = recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(actor.restaurantId(), recipeId);
        var prices = costs.findCosts(actor.restaurantId(), lines.stream().map(line -> line.getIngredientId()).toList());
        var inputs = lines.stream().map(line -> new RecipeCostCalculator.IngredientInput(
                line.getIngredientId(), line.getIngredientSkuSnapshot(), line.getIngredientNameSnapshot(),
                line.getIngredientUnitSnapshot(), line.getQuantity(), line.getWastePercent(),
                prices.get(line.getIngredientId()))).toList();
        RecipeCostCalculator.Result result;
        try {
            result = calculator.calculate(recipe.getYieldQuantity(), inputs);
        } catch (RecipeCostCalculationException failure) {
            throw new ConflictException(failure.getCode(), failure.getMessage());
        }
        var restaurant = restaurants.findByIdAndDeletedAtIsNull(actor.restaurantId()).orElseThrow(() ->
                new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        return new RecipeCostEstimateResponse(menuItemId, recipe.getId(), recipe.getRecipeVersion(), recipe.isActive(),
                recipe.getYieldQuantity(), recipe.getYieldUnit(), restaurant.getCurrencyCode(), costs.costSource(),
                result.totalBatchCost(), result.costPerYieldUnit(), result.ingredients(), result.warnings(), clock.instant());
    }
}
