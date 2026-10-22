package com.possaas.modules.inventory.repository;

import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** A batch, tenant-scoped read projection, including ineligible rows needed to close history. */
@Repository @RequiredArgsConstructor
public class LowStockAlertBalanceQuery {
    private final NamedParameterJdbcTemplate jdbc;

    public List<Balance> find(UUID tenant,Collection<UUID> warehouses,Collection<UUID> ingredients) {
        String sql="""
            SELECT b.warehouse_id,b.item_id,w.code,w.name AS warehouse_name,w.is_active,
                w.deleted_at AS warehouse_deleted,i.sku,i.name AS ingredient_name,i.base_unit,
                i.item_type,i.track_inventory,i.deleted_at AS ingredient_deleted,b.quantity,b.min_quantity
            FROM inventory_balances b
            JOIN warehouses w ON w.restaurant_id=b.restaurant_id AND w.id=b.warehouse_id
            JOIN items i ON i.restaurant_id=b.restaurant_id AND i.id=b.item_id
            WHERE b.restaurant_id=:tenant
            """;
        var params=new HashMap<String,Object>(); params.put("tenant",tenant);
        if (!warehouses.isEmpty()) {sql+=" AND b.warehouse_id IN (:warehouses)";params.put("warehouses",warehouses);}
        if (!ingredients.isEmpty()) {sql+=" AND b.item_id IN (:ingredients)";params.put("ingredients",ingredients);}
        return jdbc.query(sql+" ORDER BY b.warehouse_id,b.item_id",params,(rs,row)->new Balance(
            rs.getObject("warehouse_id",UUID.class),rs.getObject("item_id",UUID.class),rs.getString("code"),
            rs.getString("warehouse_name"),rs.getBoolean("is_active"),rs.getTimestamp("warehouse_deleted")!=null,
            rs.getString("sku"),rs.getString("ingredient_name"),rs.getString("base_unit"),
            "INGREDIENT".equals(rs.getString("item_type")) && rs.getBoolean("track_inventory"),
            rs.getTimestamp("ingredient_deleted")!=null,rs.getBigDecimal("quantity"),rs.getBigDecimal("min_quantity")));
    }

    public record Key(UUID warehouseId,UUID ingredientId) {}
    public record Balance(UUID warehouseId,UUID ingredientId,String warehouseCode,String warehouseName,
            boolean warehouseActive,boolean warehouseDeleted,String sku,String name,String unit,
            boolean tracked,boolean ingredientDeleted,BigDecimal quantity,BigDecimal threshold) {
        public Key key() {return new Key(warehouseId,ingredientId);}
        public boolean eligible() {return warehouseActive && !warehouseDeleted && tracked && !ingredientDeleted;}
    }
}
