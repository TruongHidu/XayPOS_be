package com.possaas.modules.menu;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryStockItemCatalogAdapterTest {
    private final ItemRepository items = mock(ItemRepository.class);
    private final InventoryStockItemCatalogAdapter adapter =
            new InventoryStockItemCatalogAdapter(items, new StockTrackingModeResolver());
    private final UUID tenant = UUID.randomUUID();

    private Item item(ItemType type) {
        var item = new Item();
        item.initialize(tenant, Instant.parse("2026-01-01T00:00:00Z"));
        item.setId(UUID.randomUUID());
        item.setItemType(type);
        item.setTrackInventory(true);
        item.setName("Stock item");
        item.setBaseUnit("piece");
        return item;
    }

    @Test void loadsMixedItemsInOneTenantScopedBatchAndDeduplicatesIds() {
        var ingredient = item(ItemType.INGREDIENT);
        var direct = item(ItemType.MENU_ITEM);
        var ids = Set.of(ingredient.getId(), direct.getId());
        when(items.findAllByRestaurantIdAndIdIn(eq(tenant), anyCollection()))
                .thenReturn(List.of(ingredient, direct));
        var found = adapter.requireTrackedBatch(tenant,
                List.of(ingredient.getId(), direct.getId(), direct.getId()));
        assertThat(found).hasSize(2);
        assertThat(found.get(direct.getId()).itemType()).isEqualTo("MENU_ITEM");
        verify(items).findAllByRestaurantIdAndIdIn(tenant, ids);
        verifyNoMoreInteractions(items);
    }

    @Test void emptyBatchDoesNotQuery() {
        assertThat(adapter.requireTrackedBatch(tenant, List.of())).isEmpty();
        verifyNoInteractions(items);
    }

    @Test void neverReturnsPartialBatchForUntrackedItems() {
        var direct = item(ItemType.MENU_ITEM);
        direct.setTrackInventory(false);
        when(items.findAllByRestaurantIdAndIdIn(eq(tenant), anyCollection())).thenReturn(List.of(direct));
        assertThatThrownBy(() -> adapter.requireTrackedBatch(tenant, List.of(direct.getId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void rejectsForeignTenantEvenIfARepositoryResultIsMalformed() {
        var direct = item(ItemType.MENU_ITEM);
        when(items.findAllByRestaurantIdAndIdIn(any(), anyCollection())).thenReturn(List.of(direct));
        assertThatThrownBy(() -> adapter.requireTrackedBatch(UUID.randomUUID(), List.of(direct.getId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
