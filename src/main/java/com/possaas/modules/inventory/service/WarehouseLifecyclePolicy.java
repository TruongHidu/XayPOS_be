package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.Warehouse;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.inventory.repository.OrderItemInventoryAllocationRepository;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WarehouseLifecyclePolicy {
    private final InventoryBalanceRepository balances;
    private final OrderItemInventoryAllocationRepository allocations;

    public void requireVersion(Warehouse warehouse, long expectedVersion) {
        if (warehouse.getVersion() != expectedVersion)
            throw new ConflictException("CONCURRENT_WAREHOUSE_UPDATE",
                    "Warehouse changed; reload before retrying");
    }

    public void requireCanDisable(Warehouse warehouse) {
        requireNoReservations(warehouse);
        if (warehouse.isDefaultWarehouse())
            throw new ConflictException("DEFAULT_WAREHOUSE_CANNOT_BE_DISABLED",
                    "Select another default warehouse before disabling this warehouse");
        requireNoStock(warehouse);
    }

    public void requireCanDelete(Warehouse warehouse, boolean hasOtherActiveWarehouse) {
        requireNoReservations(warehouse);
        requireNoStock(warehouse);
        if (warehouse.isDefaultWarehouse() && hasOtherActiveWarehouse)
            throw new ConflictException("DEFAULT_WAREHOUSE_CANNOT_BE_DELETED",
                    "Select another default warehouse before deleting this warehouse");
    }

    public void requireCanBecomeDefault(Warehouse warehouse) {
        if (!warehouse.isActive() || warehouse.getDeletedAt() != null)
            throw new ConflictException("WAREHOUSE_INACTIVE", "Only an active warehouse can be default");
    }

    private void requireNoStock(Warehouse warehouse) {
        if (balances.hasNonZeroStockInWarehouse(warehouse.getRestaurantId(), warehouse.getId(), BigDecimal.ZERO))
            throw new ConflictException("WAREHOUSE_HAS_STOCK", "Warehouse still contains inventory");
    }

    private void requireNoReservations(Warehouse warehouse) {
        if (allocations.hasReservedInWarehouse(warehouse.getRestaurantId(), warehouse.getId()))
            throw new ConflictException("WAREHOUSE_HAS_RESERVATIONS", "Warehouse still has inventory reservations");
    }
}
