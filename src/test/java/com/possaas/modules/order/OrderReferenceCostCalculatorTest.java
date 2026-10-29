package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery.Price;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.RecipeUsageQuantityCalculator;
import com.possaas.modules.order.service.cost.OrderReferenceCostCalculator;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderReferenceCostCalculatorTest {
    final OrderReferenceCostCalculator calculator=new OrderReferenceCostCalculator();
    final UUID item=UUID.randomUUID();

    @ParameterizedTest @CsvSource({"25.15,2.5,62.88","0.00,3,0.00","0.01,0.5,0.01"})
    void manualUsesConfiguredUnitPriceAndHalfUpTotal(String price,String quantity,String total) {
        var result=calculator.manual(item,"portion",n(quantity),price("portion",price,true));
        assertThat(result.version()).isEqualTo((short)1);assertThat(result.status()).isEqualTo(OrderCostStatus.COMPLETE);
        assertThat(result.reason()).isNull();assertThat(result.unitCost()).isEqualByComparingTo(price);
        assertThat(result.totalCost()).isEqualByComparingTo(total);
    }

    @Test void unknownManualPriceHasNoAmountsAndIsNeverCoalescedToZero() {
        var result=calculator.manual(item,"portion",n("3"),price("portion","0",false));
        assertThat(result.status()).isEqualTo(OrderCostStatus.INCOMPLETE);
        assertThat(result.reason()).isEqualTo(OrderCostUnavailableReason.MANUAL_COST_NOT_CONFIGURED);
        assertThat(result.unitCost()).isNull();assertThat(result.totalCost()).isNull();
    }

    @Test void recipeUsesFinalRequiredQuantitiesIncludingWasteYieldAndFractionalOrderOnlyOnce() {
        var required=new RecipeUsageQuantityCalculator().calculate(n("1000"),n("5"),n("2.5"),n("3"));
        assertThat(required).isEqualByComparingTo("875.000");
        var usage=calculator.ingredient(item,"g",required,price("g","1.25",true));
        var result=calculator.recipe(n("2.5"),List.of(usage));
        assertThat(usage.lineCost()).isEqualByComparingTo("1093.75");
        assertThat(result.totalCost()).isEqualByComparingTo("1093.75");assertThat(result.unitCost()).isEqualByComparingTo("437.50");
    }

    @Test void tinyRoundedUsageAmountsAreSummedBeforeDisplayUnitIsRounded() {
        var first=calculator.ingredient(item,"g",n("0.015"),price("g","1",true));
        var second=calculator.ingredient(item,"g",n("0.015"),price("g","1",true));
        var result=calculator.recipe(n("3"),List.of(first,second));
        assertThat(first.lineCost()).isEqualByComparingTo("0.02");assertThat(second.lineCost()).isEqualByComparingTo("0.02");
        assertThat(result.totalCost()).isEqualByComparingTo("0.04");assertThat(result.unitCost()).isEqualByComparingTo("0.01");
        assertThat(result.unitCost().multiply(n("3"))).isNotEqualByComparingTo(result.totalCost());
    }

    @Test void ceilingVerySmallUsageCanStillHaveKnownZeroRoundedLineCost() {
        var required=new RecipeUsageQuantityCalculator().calculate(n("5"),n("5"),n("0.001"),n("3"));
        assertThat(required).isEqualByComparingTo("0.002");
        var usage=calculator.ingredient(item,"g",required,price("g","1.25",true));
        assertThat(usage.configured()).isTrue();assertThat(usage.lineCost()).isEqualByComparingTo("0.00");
        assertThat(calculator.recipe(n("0.001"),List.of(usage)).status()).isEqualTo(OrderCostStatus.COMPLETE);
    }

    @Test void missingIngredientPriceKeepsKnownUsageButHidesFullHeaderAmounts() {
        var known=calculator.ingredient(item,"g",n("315"),price("g","10",true));
        var missing=calculator.ingredient(item,"g",n("1.5"),price("g","0",false));
        var result=calculator.recipe(n("3"),List.of(known,missing));
        assertThat(known.configured()).isTrue();assertThat(known.unitCost()).isEqualByComparingTo("10");assertThat(known.lineCost()).isEqualByComparingTo("3150");
        assertThat(missing.configured()).isFalse();assertThat(missing.unitCost()).isNull();assertThat(missing.lineCost()).isNull();
        assertThat(result.status()).isEqualTo(OrderCostStatus.INCOMPLETE);assertThat(result.reason()).isEqualTo(OrderCostUnavailableReason.INGREDIENT_COST_NOT_CONFIGURED);
        assertThat(result.unitCost()).isNull();assertThat(result.totalCost()).isNull();
    }

    @Test void configuredZeroIngredientIsCompleteAndRetainsItsKnownFlag() {
        var usage=calculator.ingredient(item,"g",n("315"),price("g","0.00",true));
        assertThat(usage.configured()).isTrue();assertThat(usage.unitCost()).isEqualByComparingTo("0.00");
        var result=calculator.recipe(n("3"),List.of(usage));
        assertThat(result.status()).isEqualTo(OrderCostStatus.COMPLETE);assertThat(result.totalCost()).isEqualByComparingTo("0");
    }

    @ParameterizedTest @ValueSource(strings={"missing","identity","negative","fraction","price-overflow","unknown-nonzero"})
    void invalidCatalogueDataIsAConflictInsteadOfAnUnknownPrice(String defect) {
        Price source=switch(defect) {
            case "missing"->null;
            case "identity"->new Price(UUID.randomUUID(),"g",BigDecimal.TEN,true);
            case "negative"->price("g","-1",true);
            case "fraction"->price("g","0.001",true);
            case "price-overflow"->price("g","1000000000000.00",true);
            default->price("g","10",false);
        };
        assertCode(()->calculator.ingredient(item,"g",n("1"),source),"INVALID_ORDER_COST_SNAPSHOT_DATA");
    }

    @Test void sourceUnitsAndCurrenciesMustMatchExactlyWithoutConversion() {
        assertCode(()->calculator.ingredient(item,"g",n("1"),price("kg","10",true)),"ORDER_COST_UNIT_MISMATCH");
        assertCode(()->calculator.requireCurrency("VND","USD"),"ORDER_COST_CURRENCY_MISMATCH");
        assertCode(()->calculator.requireCurrency("VND",null),"ORDER_COST_CURRENCY_MISMATCH");
        calculator.requireCurrency("VND","VND");
    }

    @Test void usageAmountAndAggregateOverflowAreRejectedWithoutTruncation() {
        assertCode(()->calculator.ingredient(item,"g",n("1E30"),price("g","999999999999.99",true)),"ORDER_COST_AMOUNT_OUT_OF_RANGE");
        var largest=new OrderIngredientCostSnapshot(true,BigDecimal.ONE,n("999999999999999999999999999999999999.99"));
        assertCode(()->calculator.recipe(BigDecimal.ONE,List.of(largest,largest)),"ORDER_COST_AMOUNT_OUT_OF_RANGE");
    }

    @ParameterizedTest @ValueSource(strings={"0","-1"})
    void nonPositiveQuantityIsCorruptionRatherThanMissingConfiguration(String quantity) {
        assertCode(()->calculator.manual(item,"portion",n(quantity),price("portion","0",false)),"INVALID_ORDER_COST_SNAPSHOT_DATA");
    }

    @Test void partiallyPopulatedUsageMonetaryMetadataCannotBecomeACompleteTotal() {
        assertCode(()->calculator.recipe(BigDecimal.ONE,List.of(new OrderIngredientCostSnapshot(false,BigDecimal.ZERO,null))),"INVALID_ORDER_COST_SNAPSHOT_DATA");
        assertCode(()->calculator.recipe(BigDecimal.ONE,List.of(new OrderIngredientCostSnapshot(true,null,null))),"INVALID_ORDER_COST_SNAPSHOT_DATA");
        assertCode(()->calculator.recipe(BigDecimal.ONE,List.of()),"INVALID_ORDER_COST_SNAPSHOT_DATA");
    }

    Price price(String unit,String value,boolean configured){return new Price(item,unit,n(value),configured);}
    static BigDecimal n(String value){return new BigDecimal(value);}
    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){
        assertThatThrownBy(action).isInstanceOfSatisfying(ConflictException.class,failure->assertThat(failure.getCode()).isEqualTo(code));
    }
}
