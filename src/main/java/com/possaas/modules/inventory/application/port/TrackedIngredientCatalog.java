package com.possaas.modules.inventory.application.port;

import java.util.UUID;
import java.util.Collection;
import java.util.Map;

public interface TrackedIngredientCatalog {
    java.util.List<TrackedIngredientSnapshot> findAllTracked(UUID restaurantId);
    TrackedIngredientSnapshot requireTracked(UUID restaurantId, UUID ingredientId);

    Map<UUID, TrackedIngredientSnapshot> requireTrackedBatch(UUID restaurantId, Collection<UUID> ingredientIds);

    record TrackedIngredientSnapshot(
            UUID id,
            String sku,
            String name,
            String baseUnit,
            boolean active,
            boolean trackInventory,
            boolean deleted) {
    }
}
