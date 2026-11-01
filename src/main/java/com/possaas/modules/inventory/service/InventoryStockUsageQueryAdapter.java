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
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    @Override public boolean hasDraftDocuments(UUID tenant, UUID itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM stock_document_items i JOIN stock_documents d ON d.restaurant_id=i.restaurant_id AND d.id=i.stock_document_id WHERE i.restaurant_id=:tenant AND i.ingredient_id=:item AND d.status='DRAFT')", java.util.Map.of("tenant",tenant,"item",itemId),Boolean.class));
    }
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
