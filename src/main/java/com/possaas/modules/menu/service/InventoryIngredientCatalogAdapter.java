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

    @Override
    public java.util.List<TrackedIngredientSnapshot> findAllTracked(UUID restaurantId) {
        return items.findAllTrackedIngredients(restaurantId).stream().map(value -> new TrackedIngredientSnapshot(
            value.getId(),value.getSku(),value.getName(),value.getBaseUnit(),value.isActive(),true,false)).toList();
    }

    @Override
    public TrackedIngredientSnapshot requireTracked(UUID restaurantId, UUID ingredientId) {
        var item = items.findByIdAndRestaurantIdAndItemType(ingredientId, restaurantId, ItemType.INGREDIENT)
                .filter(value -> value.getDeletedAt() == null && value.isTrackInventory())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "TRACKED_INGREDIENT_NOT_FOUND", "Tracked ingredient not found"));
        return new TrackedIngredientSnapshot(item.getId(), item.getSku(), item.getName(), item.getBaseUnit(),
                item.isActive(), item.isTrackInventory(), false);
    }

    @Override
    public Map<UUID, TrackedIngredientSnapshot> requireTrackedBatch(UUID restaurantId,
            Collection<UUID> ingredientIds) {
        Map<UUID, TrackedIngredientSnapshot> result = items.findAllByRestaurantIdAndIdIn(restaurantId, ingredientIds)
                .stream()
                .filter(value -> value.getItemType() == ItemType.INGREDIENT
                        && value.getDeletedAt() == null && value.isTrackInventory())
                .map(value -> new TrackedIngredientSnapshot(value.getId(), value.getSku(), value.getName(),
                        value.getBaseUnit(), value.isActive(), value.isTrackInventory(), false))
                .collect(Collectors.toMap(TrackedIngredientSnapshot::id, Function.identity()));
        if (result.size() != ingredientIds.stream().distinct().count())
            throw new ResourceNotFoundException("TRACKED_INGREDIENT_NOT_FOUND", "Tracked ingredient not found");
        return result;
    }
}
