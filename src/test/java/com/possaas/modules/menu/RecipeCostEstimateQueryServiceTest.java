package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.menu.dto.RecipeCostEstimateResponse;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.service.RecipeAccessService;
import com.possaas.modules.menu.service.RecipeCostEstimateQueryService;
import com.possaas.modules.menu.service.cost.IngredientCostProvider;
import com.possaas.modules.menu.service.cost.IngredientCostProvider.IngredientCost;
import com.possaas.modules.menu.service.cost.RecipeCostCalculationException;
import com.possaas.modules.menu.service.cost.RecipeCostCalculator;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RecipeCostEstimateQueryServiceTest {
    private static final UUID RESTAURANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MENU_ITEM = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RECIPE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID FIRST = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID SECOND = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant ESTIMATED_AT = Instant.parse("2026-10-19T08:00:00Z");
    private static final BigDecimal YIELD = new BigDecimal("10.000");

    private final RecipeAccessService access = mock(RecipeAccessService.class);
    private final RecipeItemRepository recipeItems = mock(RecipeItemRepository.class);
    private final IngredientCostProvider costs = mock(IngredientCostProvider.class);
    private final RecipeCostCalculator calculator = mock(RecipeCostCalculator.class);
    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final Clock clock = Clock.fixed(ESTIMATED_AT, ZoneOffset.UTC);
    private final RecipeCostEstimateQueryService service = new RecipeCostEstimateQueryService(
            access, recipeItems, costs, calculator, restaurants, clock);

    private Recipe history;
    private RecipeCostCalculator.Result calculated;

    @BeforeEach
    void selectedHistoricalRecipe() {
        var actor = new CurrentUser(UUID.randomUUID(), RESTAURANT, "owner@example.com", "OWNER",
                Set.of("RECIPE_VIEW"));
        when(access.require(false)).thenReturn(actor);
        history = new Recipe();
        history.initialize(RESTAURANT, MENU_ITEM, 2, YIELD, "portion", ESTIMATED_AT.minusSeconds(3600));
        history.setId(RECIPE);
        history.setActive(false);
        history.setVersion(17);
        when(access.recipe(RESTAURANT, MENU_ITEM, RECIPE)).thenReturn(history);
        when(recipeItems.findAllByRestaurantIdAndRecipeIdOrderByIngredientId(RESTAURANT, RECIPE))
                .thenReturn(List.of(line(FIRST, "OLD-BEEF", "Original beef", "g", "12.000", "5.00"),
                        line(SECOND, "OLD-SALT", "Original salt", "g", "1.000", "0.00")));
        when(costs.findCosts(RESTAURANT, List.of(FIRST, SECOND))).thenReturn(Map.of(
                FIRST, new IngredientCost(FIRST, "g", new BigDecimal("2.50")),
                SECOND, new IngredientCost(SECOND, "g", BigDecimal.ZERO)));
        when(costs.costSource()).thenReturn("INGREDIENT_COST_PRICE");
        calculated = new RecipeCostCalculator.Result(new BigDecimal("31.50"), new BigDecimal("3.15"),
                List.of(new RecipeCostEstimateResponse.Ingredient(FIRST, "OLD-BEEF", "Original beef", "g",
                        new BigDecimal("12.000"), new BigDecimal("5.00"), new BigDecimal("12.60000"),
                        new BigDecimal("2.50"), new BigDecimal("31.50"))),
                List.of(new RecipeCostEstimateResponse.Warning("ZERO_INGREDIENT_REFERENCE_COST", SECOND,
                        "Ingredient has zero reference cost")));
        when(calculator.calculate(eq(YIELD), anyList())).thenReturn(calculated);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(RESTAURANT);
        restaurant.setCurrencyCode("USD");
        when(restaurants.findByIdAndDeletedAtIsNull(RESTAURANT)).thenReturn(Optional.of(restaurant));
    }

    @Test
    void estimatesChosenHistoricalRecipeUsingBatchPricesTenantCurrencyAndInjectedClock() {
        var response = service.estimate(MENU_ITEM, RECIPE);

        assertThat(response.menuItemId()).isEqualTo(MENU_ITEM);
        assertThat(response.recipeId()).isEqualTo(RECIPE);
        assertThat(response.recipeVersion()).isEqualTo(2);
        assertThat(response.recipeActive()).isFalse();
        assertThat(response.yieldQuantity()).isEqualByComparingTo("10.000");
        assertThat(response.yieldUnit()).isEqualTo("portion");
        assertThat(response.currencyCode()).isEqualTo("USD");
        assertThat(response.costSource()).isEqualTo("INGREDIENT_COST_PRICE");
        assertThat(response.estimatedAt()).isEqualTo(ESTIMATED_AT);
        assertThat(response.totalBatchCost()).isEqualByComparingTo("31.50");
        assertThat(response.costPerYieldUnit()).isEqualByComparingTo("3.15");
        assertThat(response.ingredients()).containsExactlyElementsOf(calculated.ingredients());
        assertThat(response.warnings()).containsExactlyElementsOf(calculated.warnings());

        verify(access).require(false);
        verify(access).menuItem(RESTAURANT, MENU_ITEM);
        verify(access).recipe(RESTAURANT, MENU_ITEM, RECIPE);
        verify(access, never()).require(true);
        verify(access, never()).activeRecipe(RESTAURANT, MENU_ITEM);
        verify(access, never()).recipeForUpdate(RESTAURANT, MENU_ITEM, RECIPE);
        verify(recipeItems).findAllByRestaurantIdAndRecipeIdOrderByIngredientId(RESTAURANT, RECIPE);
        verify(costs).findCosts(RESTAURANT, List.of(FIRST, SECOND));
        verify(calculator).calculate(YIELD, List.of(
                new RecipeCostCalculator.IngredientInput(FIRST, "OLD-BEEF", "Original beef", "g",
                        new BigDecimal("12.000"), new BigDecimal("5.00"),
                        new IngredientCost(FIRST, "g", new BigDecimal("2.50"))),
                new RecipeCostCalculator.IngredientInput(SECOND, "OLD-SALT", "Original salt", "g",
                        new BigDecimal("1.000"), new BigDecimal("0.00"),
                        new IngredientCost(SECOND, "g", BigDecimal.ZERO))));
        assertThat(history.getRecipeVersion()).isEqualTo(2);
        assertThat(history.getVersion()).isEqualTo(17);
    }

    @Test
    void translatesIdentifiedCalculationFailuresToStableConflictErrors() {
        for (String code : List.of("INVALID_RECIPE_COST_DATA", "INGREDIENT_COST_UNAVAILABLE",
                "INVALID_INGREDIENT_REFERENCE_COST", "INGREDIENT_COST_UNIT_MISMATCH")) {
            doThrow(new RecipeCostCalculationException(code, "Meaningful calculation failure"))
                    .when(calculator).calculate(eq(YIELD), anyList());

            assertThatThrownBy(() -> service.estimate(MENU_ITEM, RECIPE))
                    .isInstanceOf(ConflictException.class)
                    .satisfies(error -> {
                        ConflictException conflict = (ConflictException) error;
                        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(conflict.getCode()).isEqualTo(code);
                        assertThat(conflict.getMessage()).isEqualTo("Meaningful calculation failure");
                    });
        }
    }

    @Test
    void missingRestaurantCannotProduceAnEstimateWithAssumedCurrency() {
        when(restaurants.findByIdAndDeletedAtIsNull(RESTAURANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.estimate(MENU_ITEM, RECIPE))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(error -> {
                    ResourceNotFoundException missing = (ResourceNotFoundException) error;
                    assertThat(missing.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(missing.getCode()).isEqualTo("RESTAURANT_NOT_FOUND");
                });
    }

    private static RecipeItem line(UUID ingredientId, String sku, String name, String unit,
            String quantity, String waste) {
        RecipeItem line = new RecipeItem();
        line.setRestaurantId(RESTAURANT);
        line.setRecipeId(RECIPE);
        line.setIngredientId(ingredientId);
        line.setIngredientSkuSnapshot(sku);
        line.setIngredientNameSnapshot(name);
        line.setIngredientUnitSnapshot(unit);
        line.setQuantity(new BigDecimal(quantity));
        line.setWastePercent(new BigDecimal(waste));
        return line;
    }
}
