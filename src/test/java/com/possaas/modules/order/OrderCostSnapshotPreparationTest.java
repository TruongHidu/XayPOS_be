package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.modules.order.application.port.OrderReferenceCostQuery;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.cost.*;
import com.possaas.modules.order.service.cost.OrderCostSnapshotPreparation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderCostSnapshotPreparationTest {
    final UUID tenant=UUID.randomUUID(),manual=UUID.randomUUID(),food=UUID.randomUUID(),tracked=UUID.randomUUID(),untracked=UUID.randomUUID();
    final OrderReferenceCostQuery prices=mock(OrderReferenceCostQuery.class);
    final OrderCostSnapshotPreparation preparation=new OrderCostSnapshotPreparation(prices,new OrderReferenceCostCalculator());

    @Test void repeatedMenuAndIngredientIdsUseOneTenantFilteredBatchForEachCostSource() {
        var firstManual=manual("2");var secondManual=manual("3");
        var firstRecipe=recipe("3","315","1.5");var secondRecipe=recipe("2","210","1");
        when(prices.findManualCosts(tenant,List.of(manual))).thenReturn(new Prices("VND",Map.of(manual,new Price(manual,"portion",n("25"),true))));
        when(prices.findIngredientCosts(tenant,List.of(tracked,untracked))).thenReturn(new Prices("VND",Map.of(
            tracked,new Price(tracked,"g",n("10"),true),untracked,new Price(untracked,"g",n("2"),true))));

        var result=preparation.prepare(order(),List.of(firstManual,secondManual,firstRecipe,secondRecipe));

        verify(prices).findManualCosts(tenant,List.of(manual));verify(prices).findIngredientCosts(tenant,List.of(tracked,untracked));verifyNoMoreInteractions(prices);
        assertThat(result).hasSize(4);assertThat(result.get(firstManual.orderItemId()).summary().totalCost()).isEqualByComparingTo("50");
        assertThat(result.get(secondManual.orderItemId()).summary().totalCost()).isEqualByComparingTo("75");
        var first=result.get(firstRecipe.orderItemId());assertThat(first.ingredients()).hasSize(2);
        assertThat(first.summary().totalCost()).isEqualByComparingTo("3153");assertThat(first.summary().unitCost()).isEqualByComparingTo("1051");
        assertThat(result.get(secondRecipe.orderItemId()).summary().totalCost()).isEqualByComparingTo("2102");
        assertThatThrownBy(()->first.ingredients().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void featureDisabledDoesNotReadAnyLiveCostOrFallBackToManual() {
        var disabled=new Input(UUID.randomUUID(),food,"portion",n("3"),RecipeSnapshotStatus.FEATURE_DISABLED,List.of());
        var result=preparation.prepare(order(),List.of(disabled)).get(disabled.orderItemId());
        assertThat(result.summary().status()).isEqualTo(OrderCostStatus.UNAVAILABLE);
        assertThat(result.summary().reason()).isEqualTo(OrderCostUnavailableReason.RECIPE_FEATURE_DISABLED);
        assertThat(result.summary().unitCost()).isNull();assertThat(result.summary().totalCost()).isNull();assertThat(result.ingredients()).isEmpty();
        verifyNoInteractions(prices);
    }

    @Test void missingUntrackedPriceIsIncludedInCompletenessAndDoesNotEraseKnownTrackedMoney() {
        var line=recipe("3","315","1.5");
        when(prices.findIngredientCosts(eq(tenant),anyCollection())).thenReturn(new Prices("VND",Map.of(
            tracked,new Price(tracked,"g",n("10"),true),untracked,new Price(untracked,"g",BigDecimal.ZERO,false))));
        var result=preparation.prepare(order(),List.of(line)).get(line.orderItemId());
        assertThat(result.summary().status()).isEqualTo(OrderCostStatus.INCOMPLETE);assertThat(result.summary().totalCost()).isNull();
        assertThat(result.ingredients().get(tracked).lineCost()).isEqualByComparingTo("3150");
        assertThat(result.ingredients().get(untracked).configured()).isFalse();assertThat(result.ingredients().get(untracked).lineCost()).isNull();
        verify(prices,never()).findManualCosts(any(),anyCollection());
    }

    @Test void currencyMismatchAndAbsentIdentityFailInsteadOfManufacturingUnknownZero() {
        var line=manual("1");
        when(prices.findManualCosts(eq(tenant),anyCollection())).thenReturn(new Prices("USD",Map.of(manual,new Price(manual,"portion",n("10"),true))));
        OrderReferenceCostCalculatorTest.assertCode(()->preparation.prepare(order(),List.of(line)),"ORDER_COST_CURRENCY_MISMATCH");
        when(prices.findManualCosts(eq(tenant),anyCollection())).thenReturn(new Prices("VND",Map.of()));
        OrderReferenceCostCalculatorTest.assertCode(()->preparation.prepare(order(),List.of(line)),"INVALID_ORDER_COST_SNAPSHOT_DATA");
    }

    @Test void duplicateUsageIdentityIsCorruptionAndEmptyAcceptanceNeedsNoPrices() {
        assertThat(preparation.prepare(order(),List.of())).isEmpty();verifyNoInteractions(prices);
        when(prices.findIngredientCosts(eq(tenant),anyCollection())).thenReturn(new Prices("VND",Map.of(tracked,new Price(tracked,"g",BigDecimal.ONE,true))));
        var usage=new Usage(tracked,"g",BigDecimal.ONE);
        var line=new Input(UUID.randomUUID(),food,"portion",BigDecimal.ONE,RecipeSnapshotStatus.CAPTURED,List.of(usage,usage));
        OrderReferenceCostCalculatorTest.assertCode(()->preparation.prepare(order(),List.of(line)),"INVALID_ORDER_COST_SNAPSHOT_DATA");
    }

    Input manual(String quantity){return new Input(UUID.randomUUID(),manual,"portion",n(quantity),RecipeSnapshotStatus.RECIPE_DISABLED,List.of());}
    Input recipe(String quantity,String trackedQuantity,String untrackedQuantity){return new Input(UUID.randomUUID(),food,"portion",n(quantity),RecipeSnapshotStatus.CAPTURED,List.of(new Usage(tracked,"g",n(trackedQuantity)),new Usage(untracked,"g",n(untrackedQuantity))));}
    Order order(){var order=new Order();order.initialize(tenant,Instant.parse("2026-10-27T08:00:00Z"));order.setCurrencyCode("VND");return order;}
    static BigDecimal n(String value){return new BigDecimal(value);}
}
