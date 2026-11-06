package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.modules.menu.service.cost.IngredientCostProvider.IngredientCost;
import com.possaas.modules.menu.service.cost.RecipeCostCalculationException;
import com.possaas.modules.menu.service.cost.RecipeCostCalculator;
import com.possaas.modules.menu.service.cost.RecipeCostCalculator.IngredientInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipeCostCalculatorTest {
    private static final UUID BEEF = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NOODLE = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final RecipeCostCalculator calculator = new RecipeCostCalculator();

    @Test
    void calculatesOneIngredientWithNoWasteAndYieldOne() {
        var result = calculator.calculate(decimal("1"), List.of(input(BEEF, "10.000", "0", "2.50")));

        assertThat(result.ingredients()).hasSize(1);
        assertThat(result.ingredients().getFirst().grossQuantity()).isEqualByComparingTo("10.000");
        assertThat(result.ingredients().getFirst().lineCost()).isEqualByComparingTo("25.00");
        assertThat(result.totalBatchCost()).isEqualByComparingTo("25.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("25.00");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void calculatesBatchCostAcrossIngredientsBeforeDividingByYield() {
        var result = calculator.calculate(decimal("10"), List.of(
                input(BEEF, "1200.000", "5.00", "250.00"),
                input(NOODLE, "1800.000", "0", "30.00")));

        assertThat(result.ingredients().get(0).grossQuantity()).isEqualByComparingTo("1260");
        assertThat(result.ingredients().get(0).lineCost()).isEqualByComparingTo("315000.00");
        assertThat(result.ingredients().get(1).grossQuantity()).isEqualByComparingTo("1800");
        assertThat(result.ingredients().get(1).lineCost()).isEqualByComparingTo("54000.00");
        assertThat(result.totalBatchCost()).isEqualByComparingTo("369000.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("36900.00");
    }

    @Test
    void wasteIsAnAdditionToTheNetQuantityIncludingOneHundredPercent() {
        var result = calculator.calculate(decimal("1"), List.of(input(BEEF, "12", "100", "3")));

        assertThat(result.ingredients().getFirst().grossQuantity()).isEqualByComparingTo("24");
        assertThat(result.totalBatchCost()).isEqualByComparingTo("72.00");
    }

    @Test
    void preservesFractionalGrossQuantityBeforeMultiplyingByUnitCost() {
        var result = calculator.calculate(decimal("1"), List.of(input(BEEF, "1.125", "2.50", "4.00")));

        assertThat(result.ingredients().getFirst().grossQuantity()).isEqualByComparingTo("1.153125");
        assertThat(result.ingredients().getFirst().lineCost()).isEqualByComparingTo("4.61");
    }

    @Test
    void doesNotRoundTinyGrossQuantityToInventoryPrecision() {
        var result = calculator.calculate(decimal("1"), List.of(input(BEEF, "0.001", "0.01", "100000")));

        assertThat(result.ingredients().getFirst().grossQuantity()).isEqualByComparingTo("0.0010001");
        assertThat(result.ingredients().getFirst().lineCost()).isEqualByComparingTo("100.01");
    }

    @Test
    void roundsEachLineHalfUpToTwoDecimals() {
        var result = calculator.calculate(decimal("1"), List.of(input(BEEF, "0.005", "0", "1")));

        assertThat(result.ingredients().getFirst().lineCost()).isEqualByComparingTo("0.01");
        assertThat(result.ingredients().getFirst().lineCost().scale()).isEqualTo(2);
        assertThat(result.totalBatchCost().scale()).isEqualTo(2);
        assertThat(result.costPerYieldUnit().scale()).isEqualTo(2);
    }

    @Test
    void batchTotalEqualsSumOfDisplayedRoundedLines() {
        var result = calculator.calculate(decimal("1"), List.of(
                input(BEEF, "0.005", "0", "1"),
                input(NOODLE, "0.005", "0", "1")));

        assertThat(result.ingredients()).allSatisfy(line ->
                assertThat(line.lineCost()).isEqualByComparingTo("0.01"));
        assertThat(result.totalBatchCost()).isEqualByComparingTo("0.02");
        assertThat(result.totalBatchCost()).isEqualByComparingTo(result.ingredients().stream()
                .map(line -> line.lineCost()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void roundsCostPerYieldUnitHalfUp() {
        var result = calculator.calculate(decimal("8"), List.of(input(BEEF, "1", "0", "1")));

        assertThat(result.totalBatchCost()).isEqualByComparingTo("1.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("0.13");
    }

    @Test
    void supportsFractionalYieldWithoutRoundingItToAnInteger() {
        var result = calculator.calculate(decimal("2.500"), List.of(input(BEEF, "10", "0", "1")));

        assertThat(result.totalBatchCost()).isEqualByComparingTo("10.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("4.00");
    }

    @Test
    void calculatedTotalsAreNotRestrictedToItemCostColumnPrecision() {
        var result = calculator.calculate(decimal("10"), List.of(
                input(BEEF, "999999999.999", "0", "999999999999.99"),
                input(NOODLE, "999999999.999", "0", "999999999999.99")));

        assertThat(result.ingredients().getFirst().lineCost())
                .isEqualByComparingTo("999999999998990000000.00");
        assertThat(result.totalBatchCost()).isEqualByComparingTo("1999999999997980000000.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("199999999999798000000.00");
    }

    @Test
    void zeroReferenceCostIsValidAndProducesAnIngredientWarning() {
        var result = calculator.calculate(decimal("10"), List.of(input(BEEF, "12", "5", "0")));

        assertThat(result.totalBatchCost()).isEqualByComparingTo("0.00");
        assertThat(result.costPerYieldUnit()).isEqualByComparingTo("0.00");
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().code()).isEqualTo("ZERO_INGREDIENT_REFERENCE_COST");
        assertThat(result.warnings().getFirst().ingredientId()).isEqualTo(BEEF);
    }

    @Test
    void zeroWarningsIdentifyEveryZeroCostIngredientWithoutDuplicates() {
        var result = calculator.calculate(decimal("1"), List.of(
                input(BEEF, "1", "0", "0"),
                input(BEEF, "2", "0", "0"),
                input(NOODLE, "3", "0", "0")));

        assertThat(result.warnings()).extracting(warning -> warning.ingredientId())
                .containsExactly(BEEF, NOODLE);
        assertThat(result.totalBatchCost()).isEqualByComparingTo("0.00");
    }

    @Test
    void retainsInputOrderingAndRecipeSnapshotIdentity() {
        IngredientInput first = new IngredientInput(NOODLE, "OLD-SKU", "Original name", "g",
                decimal("4.000"), decimal("2.50"), new IngredientCost(NOODLE, "g", decimal("3")));
        var result = calculator.calculate(decimal("1"), List.of(first, input(BEEF, "1", "0", "1")));

        assertThat(result.ingredients()).extracting(line -> line.ingredientId()).containsExactly(NOODLE, BEEF);
        var line = result.ingredients().getFirst();
        assertThat(line.sku()).isEqualTo("OLD-SKU");
        assertThat(line.name()).isEqualTo("Original name");
        assertThat(line.unit()).isEqualTo("g");
        assertThat(line.quantity()).isEqualByComparingTo("4.000");
        assertThat(line.wastePercent()).isEqualByComparingTo("2.50");
        assertThat(line.unitCost()).isEqualByComparingTo("3");
    }

    @Test
    void rejectsMissingZeroAndNegativeYield() {
        for (BigDecimal yield : new BigDecimal[] {null, BigDecimal.ZERO, decimal("-1")}) {
            assertCode("INVALID_RECIPE_COST_DATA", () ->
                    calculator.calculate(yield, List.of(input(BEEF, "1", "0", "1"))));
        }
    }

    @Test
    void rejectsMissingOrEmptyIngredientList() {
        assertCode("INVALID_RECIPE_COST_DATA", () -> calculator.calculate(BigDecimal.ONE, null));
        assertCode("INVALID_RECIPE_COST_DATA", () -> calculator.calculate(BigDecimal.ONE, List.of()));
    }

    @Test
    void rejectsMissingZeroAndNegativeRecipeQuantity() {
        for (String quantity : new String[] {null, "0", "-0.001"}) {
            assertCode("INVALID_RECIPE_COST_DATA", () ->
                    calculator.calculate(BigDecimal.ONE, List.of(input(BEEF, quantity, "0", "1"))));
        }
    }

    @Test
    void rejectsMissingNegativeAndOverOneHundredWaste() {
        for (String waste : new String[] {null, "-0.01", "100.01"}) {
            assertCode("INVALID_RECIPE_COST_DATA", () ->
                    calculator.calculate(BigDecimal.ONE, List.of(input(BEEF, "1", waste, "1"))));
        }
    }

    @Test
    void missingReferenceDoesNotSilentlyBecomeZeroCost() {
        var missingReference = new IngredientInput(BEEF, "SKU", "Ingredient", "g", BigDecimal.ONE,
                BigDecimal.ZERO, null);

        assertCode("INGREDIENT_COST_UNAVAILABLE", () ->
                calculator.calculate(BigDecimal.ONE, List.of(missingReference)));
    }

    @Test
    void rejectsNullAndNegativeReferenceCost() {
        for (String cost : new String[] {null, "-0.01"}) {
            assertCode("INVALID_INGREDIENT_REFERENCE_COST", () ->
                    calculator.calculate(BigDecimal.ONE, List.of(input(BEEF, "1", "0", cost))));
        }
    }

    @Test
    void rejectsCostReferenceForAnotherIngredient() {
        var wrongReference = new IngredientInput(BEEF, "SKU", "Ingredient", "g", BigDecimal.ONE,
                BigDecimal.ZERO, new IngredientCost(NOODLE, "g", BigDecimal.ONE));

        assertCode("INVALID_INGREDIENT_REFERENCE_COST", () ->
                calculator.calculate(BigDecimal.ONE, List.of(wrongReference)));
    }

    @Test
    void rejectsUnitMismatchRatherThanTreatingGramsAsKilograms() {
        var wrongUnit = new IngredientInput(BEEF, "SKU", "Ingredient", "g", decimal("1000"),
                BigDecimal.ZERO, new IngredientCost(BEEF, "kg", decimal("100000")));

        assertCode("INGREDIENT_COST_UNIT_MISMATCH", () ->
                calculator.calculate(BigDecimal.ONE, List.of(wrongUnit)));
    }

    private static IngredientInput input(UUID id, String quantity, String waste, String cost) {
        return new IngredientInput(id, "SKU-" + id, "Ingredient " + id, "g", decimal(quantity),
                decimal(waste), new IngredientCost(id, "g", decimal(cost)));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static void assertCode(String expected, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(RecipeCostCalculationException.class)
                .satisfies(error -> assertThat(((RecipeCostCalculationException) error).getCode()).isEqualTo(expected));
    }
}
