package com.possaas.modules.menu.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.StockTrackingMode;
import org.springframework.stereotype.Component;

/** Resolves current configuration only. Never use this to reinterpret accepted orders. */
@Component
public class StockTrackingModeResolver {
    public StockTrackingMode menuMode(boolean trackInventory, boolean recipeEnabled) {
        if (trackInventory && recipeEnabled)
            throw new ConflictException("INVENTORY_TRACKING_MODE_CONFLICT",
                    "A menu item cannot track its own stock and apply a recipe simultaneously");
        if (trackInventory) return StockTrackingMode.DIRECT;
        return recipeEnabled ? StockTrackingMode.RECIPE : StockTrackingMode.NONE;
    }

    /** Stock eligibility is independent of menu visibility and temporary sale availability. */
    public boolean isTrackedStockItem(Item item) {
        return item != null && item.getDeletedAt() == null && item.isTrackInventory()
                && !item.isRecipeEnabled()
                && (item.getItemType() == ItemType.INGREDIENT || item.getItemType() == ItemType.MENU_ITEM);
    }
}
