package com.possaas.modules.inventory.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** INVENTORY-owned catalogue contract; callers perform their command access checks first. */
public interface TrackedStockItemCatalog {
    Map<UUID, StockItem> requireTrackedBatch(UUID restaurantId, Collection<UUID> itemIds);

    record StockItem(UUID itemId, String itemType, String sku, String name,
                     String baseUnit, boolean active) {}
}
