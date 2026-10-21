package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentTenantProvider;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.inventory.entity.Warehouse;
import com.possaas.modules.inventory.repository.WarehouseRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryAccessService {
    public static final String INVENTORY_MANAGEMENT = "INVENTORY_MANAGEMENT";
    public static final String LOW_STOCK_ALERT = "LOW_STOCK_ALERT";

    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final FeatureAccessChecker features;
    private final RestaurantRepository restaurants;
    private final WarehouseRepository warehouses;

    public CurrentUser require(boolean mutation) {
        UUID restaurantId = tenants.getRequiredRestaurantId();
        features.requireFeature(restaurantId, INVENTORY_MANAGEMENT);
        if (mutation)
            restaurants.findByIdForUpdate(restaurantId).orElseThrow(InventoryAccessService::restaurantNotFound);
        else
            restaurants.findByIdAndDeletedAtIsNull(restaurantId).orElseThrow(InventoryAccessService::restaurantNotFound);
        return users.getRequired();
    }

    public CurrentUser requireLowStock() {
        CurrentUser actor = require(false);
        features.requireFeature(actor.restaurantId(), LOW_STOCK_ALERT);
        return actor;
    }

    public Warehouse warehouse(UUID restaurantId, UUID warehouseId) {
        return warehouses.findByIdAndRestaurantIdAndDeletedAtIsNull(warehouseId, restaurantId)
                .orElseThrow(InventoryAccessService::warehouseNotFound);
    }

    public Warehouse warehouseIncludingDeleted(UUID restaurantId, UUID warehouseId) {
        return warehouses.findByIdAndRestaurantId(warehouseId, restaurantId)
                .orElseThrow(InventoryAccessService::warehouseNotFound);
    }

    private static ResourceNotFoundException restaurantNotFound() {
        return new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found");
    }

    private static ResourceNotFoundException warehouseNotFound() {
        return new ResourceNotFoundException("WAREHOUSE_NOT_FOUND", "Warehouse not found");
    }
}
