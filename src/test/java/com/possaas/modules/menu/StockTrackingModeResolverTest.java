package com.possaas.modules.menu;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.service.StockTrackingModeResolver;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class StockTrackingModeResolverTest {
    private final StockTrackingModeResolver policy = new StockTrackingModeResolver();

    @Test void distinguishesDirectRecipeAndUntrackedMenu() {
        assertThat(policy.menuMode(false, false)).isEqualTo(StockTrackingMode.NONE);
        assertThat(policy.menuMode(true, false)).isEqualTo(StockTrackingMode.DIRECT);
        assertThat(policy.menuMode(false, true)).isEqualTo(StockTrackingMode.RECIPE);
    }

    @Test void rejectsDoubleConsumptionConfigurationWithStableConflict() {
        assertThatThrownBy(() -> policy.menuMode(true, true))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVENTORY_TRACKING_MODE_CONFLICT");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
    }

    @Test void acceptsBothStockKindsWithoutDependingOnSaleAvailability() {
        for (var type : ItemType.values()) {
            var item = new Item();
            item.setItemType(type);
            item.setTrackInventory(true);
            item.setActive(false);
            item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK);
            assertThat(policy.isTrackedStockItem(item)).isTrue();
        }
    }

    @Test void excludesDeletedUntrackedAndInvalidRecipeItems() {
        var item = new Item();
        item.setItemType(ItemType.MENU_ITEM);
        assertThat(policy.isTrackedStockItem(item)).isFalse();
        item.setTrackInventory(true);
        item.setRecipeEnabled(true);
        assertThat(policy.isTrackedStockItem(item)).isFalse();
        item.setRecipeEnabled(false);
        item.setDeletedAt(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(policy.isTrackedStockItem(item)).isFalse();
        assertThat(policy.isTrackedStockItem(null)).isFalse();
    }
}
