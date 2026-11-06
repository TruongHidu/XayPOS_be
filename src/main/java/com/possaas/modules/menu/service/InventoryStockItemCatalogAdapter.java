package com.possaas.modules.menu.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.inventory.application.port.TrackedStockItemCatalog;
import com.possaas.modules.menu.repository.ItemRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryStockItemCatalogAdapter implements TrackedStockItemCatalog {
    private final ItemRepository items;
    private final StockTrackingModeResolver policy;

    @Override
    public Map<UUID, StockItem> requireTrackedBatch(UUID restaurantId, Collection<UUID> itemIds) {
        Objects.requireNonNull(restaurantId);
        Objects.requireNonNull(itemIds);
        var ids = new LinkedHashSet<>(itemIds);
        if (ids.isEmpty()) return Map.of();
        if (ids.contains(null)) throw missing();
        var found = items.findAllByRestaurantIdAndIdIn(restaurantId, ids).stream()
                .filter(item -> restaurantId.equals(item.getRestaurantId()))
                .filter(item -> ids.contains(item.getId()))
                .filter(policy::isTrackedStockItem)
                .map(item -> new StockItem(item.getId(), item.getItemType().name(), item.getSku(),
                        item.getName(), item.getBaseUnit(), item.isActive()))
                .collect(Collectors.toUnmodifiableMap(StockItem::itemId, Function.identity()));
        if (found.size() != ids.size()) throw missing();
        return found;
    }

    private static ResourceNotFoundException missing() {
        return new ResourceNotFoundException("STOCK_ITEM_NOT_FOUND", "Tracked stock item not found");
    }
}
