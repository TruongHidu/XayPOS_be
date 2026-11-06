package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.service.cost.IngredientCostPriceProvider;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IngredientCostPriceProviderTest {
    private final ItemRepository items = mock(ItemRepository.class);
    private final IngredientCostPriceProvider provider = new IngredientCostPriceProvider(items);

    @Test
    void emptyIngredientBatchDoesNotQueryRepository() {
        assertThat(provider.findCosts(UUID.randomUUID(), List.of())).isEmpty();

        verifyNoInteractions(items);
    }

    @Test
    void batchRequestUsesOneTenantScopedLookupAndKeepsZeroDistinctFromUnavailable() {
        UUID restaurantId = UUID.randomUUID();
        UUID priced = UUID.randomUUID();
        UUID zeroPriced = UUID.randomUUID();
        UUID unavailable = UUID.randomUUID();
        var requested = List.of(priced, zeroPriced, unavailable);
        when(items.findIngredientReferenceCosts(restaurantId, requested)).thenReturn(List.of(
                row(priced, "g", new BigDecimal("250.00")),
                row(zeroPriced, "ml", BigDecimal.ZERO)));

        var costs = provider.findCosts(restaurantId, requested);

        assertThat(provider.costSource()).isEqualTo("INGREDIENT_COST_PRICE");
        assertThat(costs).containsOnlyKeys(priced, zeroPriced);
        assertThat(costs.get(priced).ingredientId()).isEqualTo(priced);
        assertThat(costs.get(priced).baseUnit()).isEqualTo("g");
        assertThat(costs.get(priced).unitCost()).isEqualByComparingTo("250.00");
        assertThat(costs.get(zeroPriced).unitCost()).isEqualByComparingTo("0");
        assertThat(costs).doesNotContainKey(unavailable);
        verify(items).findIngredientReferenceCosts(restaurantId, requested);
        verifyNoMoreInteractions(items);
    }

    private static ItemRepository.IngredientReferenceCost row(UUID ingredientId, String unit, BigDecimal cost) {
        return new ItemRepository.IngredientReferenceCost() {
            @Override
            public UUID getIngredientId() {
                return ingredientId;
            }

            @Override
            public String getBaseUnit() {
                return unit;
            }

            @Override
            public boolean isCostConfigured() { return true; }

            @Override
            public BigDecimal getUnitCost() {
                return cost;
            }
        };
    }
}
