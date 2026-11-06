package com.possaas.modules.menu.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import java.util.UUID;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryIngredientCatalogAdapter implements TrackedIngredientCatalog {
    private final ItemRepository items;
    private final com.possaas.modules.inventory.application.port.TrackedStockItemCatalog stockItems;

    @Override
    public java.util.List<TrackedIngredientSnapshot> findAllTracked(UUID restaurantId) {
        return items.findAllTrackedStockItems(restaurantId).stream().map(value -> new TrackedIngredientSnapshot(
            value.getId(),value.getSku(),value.getName(),value.getBaseUnit(),value.isActive(),true,false,value.getItemType().name())).toList();
    }

    @Override public TrackedIngredientSnapshot requireTracked(UUID tenant,UUID id) {
        return requireTrackedBatch(tenant,java.util.List.of(id)).get(id);
    }
    @Override public Map<UUID,TrackedIngredientSnapshot> requireTrackedBatch(UUID tenant,Collection<UUID> ids) {
        try {
            return stockItems.requireTrackedBatch(tenant,ids).values().stream().map(i->new TrackedIngredientSnapshot(
                i.itemId(),i.sku(),i.name(),i.baseUnit(),i.active(),true,false,i.itemType()))
                .collect(Collectors.toUnmodifiableMap(TrackedIngredientSnapshot::id,Function.identity()));
        } catch(ResourceNotFoundException missing) {
            throw new ResourceNotFoundException("TRACKED_INGREDIENT_NOT_FOUND","Tracked stock item not found");
        }
    }
}
