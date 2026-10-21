package com.possaas.modules.menu.application.port;

import java.util.UUID;

public interface InventoryStockUsageQuery {
    boolean hasNonZeroStock(UUID restaurantId, UUID ingredientId);
    boolean hasReservations(UUID restaurantId, UUID ingredientId);
}
