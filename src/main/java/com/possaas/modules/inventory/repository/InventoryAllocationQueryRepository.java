package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.dto.InventoryAllocationResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryAllocationQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Optional<InventoryAllocationResponse> find(UUID tenant, UUID orderId, UUID itemId) {
        var params = Map.of("tenant", tenant, "orderId", orderId, "itemId", itemId);
        List<InventoryAllocationResponse> headers = jdbc.query("""
                SELECT id,order_id,order_item_id,recipe_snapshot_id,status,skip_reason,warehouse_id,
                       reserved_at,consumed_at,released_at
                FROM order_item_inventory_allocations
                WHERE restaurant_id=:tenant AND order_id=:orderId AND order_item_id=:itemId
                """, params, (rs, row) -> new InventoryAllocationResponse(
                    rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                    rs.getObject("order_item_id", UUID.class), rs.getObject("recipe_snapshot_id", UUID.class),
                    rs.getString("status"), rs.getString("skip_reason"), rs.getObject("warehouse_id", UUID.class),
                    instant(rs, "reserved_at"), instant(rs, "consumed_at"), instant(rs, "released_at"), List.of()));
        if (headers.isEmpty()) return Optional.empty();
        var header = headers.getFirst();
        var details = jdbc.query("""
                SELECT id,recipe_usage_id,ingredient_id,ingredient_sku_snapshot,
                       ingredient_name_snapshot,ingredient_unit_snapshot,quantity
                FROM inventory_reservations WHERE restaurant_id=:tenant AND allocation_id=:allocationId
                ORDER BY ingredient_id,id
                """, Map.of("tenant", tenant, "allocationId", header.allocationId()),
                (rs, row) -> new InventoryAllocationResponse.Ingredient(
                    rs.getObject("id", UUID.class), rs.getObject("recipe_usage_id", UUID.class),
                    rs.getObject("ingredient_id", UUID.class), rs.getString("ingredient_sku_snapshot"),
                    rs.getString("ingredient_name_snapshot"), rs.getString("ingredient_unit_snapshot"),
                    rs.getBigDecimal("quantity")));
        return Optional.of(new InventoryAllocationResponse(header.allocationId(), header.orderId(),
                header.orderItemId(), header.recipeSnapshotId(), header.status(), header.skipReason(),
                header.warehouseId(), header.reservedAt(), header.consumedAt(), header.releasedAt(), details));
    }

    public boolean hasReservedIngredient(UUID tenant, UUID ingredientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM inventory_reservations r
                    JOIN order_item_inventory_allocations a ON a.restaurant_id=r.restaurant_id AND a.id=r.allocation_id
                    WHERE r.restaurant_id=:tenant AND r.ingredient_id=:ingredientId AND a.status='RESERVED'
                )
                """, Map.of("tenant", tenant, "ingredientId", ingredientId), Boolean.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
