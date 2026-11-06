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
                       reserved_at,consumed_at,released_at,direct_stock_snapshot
                FROM order_item_inventory_allocations
                WHERE restaurant_id=:tenant AND order_id=:orderId AND order_item_id=:itemId
                """, params, (rs, row) -> new InventoryAllocationResponse(
                    rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                    rs.getObject("order_item_id", UUID.class), rs.getObject("recipe_snapshot_id", UUID.class),
                    rs.getString("status"), rs.getString("skip_reason"), rs.getObject("warehouse_id", UUID.class),
                    instant(rs, "reserved_at"), instant(rs, "consumed_at"), instant(rs, "released_at"), List.of(),rs.getBoolean("direct_stock_snapshot")));
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
                    header.directStockSnapshot()?null:rs.getObject("ingredient_id", UUID.class), rs.getString("ingredient_sku_snapshot"),
                    rs.getString("ingredient_name_snapshot"), rs.getString("ingredient_unit_snapshot"),
                    rs.getBigDecimal("quantity"),rs.getObject("ingredient_id",UUID.class),header.directStockSnapshot()?"MENU_ITEM":"INGREDIENT"));
        return Optional.of(new InventoryAllocationResponse(header.allocationId(), header.orderId(),
                header.orderItemId(), header.recipeSnapshotId(), header.status(), header.skipReason(),
                header.warehouseId(), header.reservedAt(), header.consumedAt(), header.releasedAt(), details,header.directStockSnapshot()));
    }

    public boolean isDirect(UUID tenant,UUID orderId,UUID itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM order_items oi LEFT JOIN order_item_inventory_allocations a ON a.restaurant_id=oi.restaurant_id AND a.order_item_id=oi.id LEFT JOIN items i ON i.restaurant_id=oi.restaurant_id AND i.id=oi.item_id WHERE oi.restaurant_id=:tenant AND oi.order_id=:order AND oi.id=:item AND (a.direct_stock_snapshot OR (oi.sent_to_kitchen_at IS NULL AND i.item_type='MENU_ITEM' AND i.track_inventory AND NOT i.recipe_enabled AND i.deleted_at IS NULL)))",Map.of("tenant",tenant,"order",orderId,"item",itemId),Boolean.class));
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
