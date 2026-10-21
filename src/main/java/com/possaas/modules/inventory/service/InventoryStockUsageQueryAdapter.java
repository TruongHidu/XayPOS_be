package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.inventory.repository.InventoryAllocationQueryRepository;
import com.possaas.modules.menu.application.port.InventoryStockUsageQuery;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InventoryStockUsageQueryAdapter implements InventoryStockUsageQuery {
    private final InventoryBalanceRepository balances;
    private final InventoryAllocationQueryRepository allocations;

    @Override
    public boolean hasNonZeroStock(UUID restaurantId, UUID ingredientId) {
        return balances.hasNonZeroStockForItem(restaurantId, ingredientId, BigDecimal.ZERO);
    }

    @Override
    public boolean hasReservations(UUID restaurantId, UUID ingredientId) {
        return allocations.hasReservedIngredient(restaurantId, ingredientId);
    }
}
