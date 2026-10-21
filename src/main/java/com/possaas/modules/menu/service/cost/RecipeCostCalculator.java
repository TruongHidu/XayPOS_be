package com.possaas.modules.menu.service.cost;

import com.possaas.modules.menu.dto.RecipeCostEstimateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RecipeCostCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public Result calculate(BigDecimal yieldQuantity, List<IngredientInput> inputs) {
        if (yieldQuantity == null || yieldQuantity.signum() <= 0 || inputs == null || inputs.isEmpty())
            throw invalidRecipe();

        List<RecipeCostEstimateResponse.Ingredient> lines = new ArrayList<>(inputs.size());
        List<RecipeCostEstimateResponse.Warning> warnings = new ArrayList<>();
        Set<UUID> warned = new HashSet<>();
        BigDecimal total = new BigDecimal("0.00");
        for (IngredientInput input : inputs) {
            validate(input);
            BigDecimal unitCost = input.referenceCost().unitCost();
            // Division by 100 terminates exactly: retain the recipe's full gross quantity precision.
            BigDecimal gross = input.quantity().multiply(BigDecimal.ONE.add(input.wastePercent().movePointLeft(2)));
            BigDecimal lineCost = gross.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            total = total.add(lineCost);
            lines.add(new RecipeCostEstimateResponse.Ingredient(input.ingredientId(), input.sku(), input.name(),
                    input.unit(), input.quantity(), input.wastePercent(), gross, unitCost, lineCost));
            if (unitCost.signum() == 0 && warned.add(input.ingredientId())) {
                warnings.add(new RecipeCostEstimateResponse.Warning("ZERO_INGREDIENT_REFERENCE_COST",
                        input.ingredientId(), "Nguyên liệu đang có giá vốn tham chiếu bằng 0"));
            }
        }
        return new Result(total, total.divide(yieldQuantity, 2, RoundingMode.HALF_UP), lines, warnings);
    }

    private void validate(IngredientInput input) {
        if (input == null || input.ingredientId() == null || input.unit() == null || input.unit().isBlank()
                || input.quantity() == null || input.quantity().signum() <= 0 || input.wastePercent() == null
                || input.wastePercent().signum() < 0 || input.wastePercent().compareTo(HUNDRED) > 0)
            throw invalidRecipe();
        var price = input.referenceCost();
        if (price == null)
            throw new RecipeCostCalculationException("INGREDIENT_COST_UNAVAILABLE",
                    "Ingredient reference cost is unavailable");
        if (!input.ingredientId().equals(price.ingredientId()) || price.unitCost() == null
                || price.unitCost().signum() < 0)
            throw new RecipeCostCalculationException("INVALID_INGREDIENT_REFERENCE_COST",
                    "Ingredient reference cost is invalid");
        if (!input.unit().equals(price.baseUnit()))
            throw new RecipeCostCalculationException("INGREDIENT_COST_UNIT_MISMATCH",
                    "Ingredient price unit does not match the recipe snapshot unit");
    }

    private static RecipeCostCalculationException invalidRecipe() {
        return new RecipeCostCalculationException("INVALID_RECIPE_COST_DATA",
                "Recipe yield, ingredient quantity or waste data is invalid");
    }

    public record IngredientInput(UUID ingredientId, String sku, String name, String unit,
            BigDecimal quantity, BigDecimal wastePercent, IngredientCostProvider.IngredientCost referenceCost) {}

    public record Result(BigDecimal totalBatchCost, BigDecimal costPerYieldUnit,
            List<RecipeCostEstimateResponse.Ingredient> ingredients,
            List<RecipeCostEstimateResponse.Warning> warnings) {
        public Result {
            ingredients = List.copyOf(ingredients);
            warnings = List.copyOf(warnings);
        }
    }
}
