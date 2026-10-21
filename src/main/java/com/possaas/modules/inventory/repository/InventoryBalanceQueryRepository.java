package com.possaas.modules.inventory.repository;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.dto.InventoryBalanceResponse;
import com.possaas.modules.inventory.dto.InventoryBalanceSearch;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryBalanceQueryRepository {
    private static final Set<String> SORTS = Set.of(
            "ingredientName", "sku", "quantity", "minQuantity", "updatedAt");
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "ingredientName", "lower(i.name)",
            "sku", "lower(coalesce(i.sku, ''))",
            "quantity", "coalesce(b.quantity, 0)",
            "minQuantity", "coalesce(b.min_quantity, 0)",
            "updatedAt", "b.updated_at");

    private final NamedParameterJdbcTemplate jdbc;

    public PageResult list(UUID restaurantId, UUID warehouseId, InventoryBalanceSearch search) {
        String from = """
                FROM items i
                JOIN warehouses w ON w.id = :warehouseId
                    AND w.restaurant_id = :restaurantId AND w.deleted_at IS NULL
                LEFT JOIN inventory_balances b ON b.restaurant_id = i.restaurant_id
                    AND b.warehouse_id = w.id AND b.item_id = i.id
                WHERE i.restaurant_id = :restaurantId
                  AND i.item_type = 'INGREDIENT'
                  AND i.track_inventory = true
                  AND i.deleted_at IS NULL
                """;
        QueryParts parts = filters(from, restaurantId, warehouseId, search, false);
        String order = order(search.sortBy(), search.direction());
        var parameters = new HashMap<>(parts.parameters());
        parameters.put("limit", search.size());
        parameters.put("offset", (long) search.page() * search.size());
        List<InventoryBalanceResponse> content = jdbc.query("""
                SELECT b.id AS balance_id, w.id AS warehouse_id, w.code AS warehouse_code,
                       i.id AS ingredient_id, i.sku AS ingredient_sku, i.name AS ingredient_name,
                       i.base_unit, i.is_active AS ingredient_active,
                       coalesce(b.quantity, 0.000) AS quantity,
                       coalesce(b.reserved_quantity, 0.000) AS reserved_quantity,
                       coalesce(b.min_quantity, 0.000) AS min_quantity,
                       b.version, b.created_at, b.updated_at
                """ + parts.sql() + order + " LIMIT :limit OFFSET :offset", parameters,
                (rs, rowNum) -> map(rs));
        Long count = jdbc.queryForObject("SELECT count(*) " + parts.sql(), parts.parameters(), Long.class);
        return new PageResult(content, count == null ? 0 : count);
    }

    public Optional<InventoryBalanceResponse> detail(UUID restaurantId, UUID warehouseId, UUID ingredientId) {
        var parameters = Map.of(
                "restaurantId", restaurantId,
                "warehouseId", warehouseId,
                "ingredientId", ingredientId);
        List<InventoryBalanceResponse> rows = jdbc.query("""
                SELECT b.id AS balance_id, w.id AS warehouse_id, w.code AS warehouse_code,
                       i.id AS ingredient_id, i.sku AS ingredient_sku, i.name AS ingredient_name,
                       i.base_unit, i.is_active AS ingredient_active,
                       coalesce(b.quantity, 0.000) AS quantity,
                       coalesce(b.reserved_quantity, 0.000) AS reserved_quantity,
                       coalesce(b.min_quantity, 0.000) AS min_quantity,
                       b.version, b.created_at, b.updated_at
                FROM items i
                JOIN warehouses w ON w.id = :warehouseId
                    AND w.restaurant_id = :restaurantId AND w.deleted_at IS NULL
                LEFT JOIN inventory_balances b ON b.restaurant_id = i.restaurant_id
                    AND b.warehouse_id = w.id AND b.item_id = i.id
                WHERE i.restaurant_id = :restaurantId AND i.id = :ingredientId
                  AND i.item_type = 'INGREDIENT' AND i.track_inventory = true
                  AND i.deleted_at IS NULL
                """, parameters, (rs, rowNum) -> map(rs));
        return rows.stream().findFirst();
    }

    public PageResult lowStock(UUID restaurantId, InventoryBalanceSearch search) {
        String from = """
                FROM inventory_balances b
                JOIN warehouses w ON w.id = b.warehouse_id AND w.restaurant_id = b.restaurant_id
                JOIN items i ON i.id = b.item_id AND i.restaurant_id = b.restaurant_id
                WHERE b.restaurant_id = :restaurantId
                  AND w.is_active = true AND w.deleted_at IS NULL
                  AND i.item_type = 'INGREDIENT' AND i.track_inventory = true AND i.deleted_at IS NULL
                  AND b.min_quantity > 0 AND b.quantity <= b.min_quantity
                """;
        QueryParts parts = filters(from, restaurantId, search.warehouseId(), search, true);
        String order = order(search.sortBy(), search.direction());
        var parameters = new HashMap<>(parts.parameters());
        parameters.put("limit", search.size());
        parameters.put("offset", (long) search.page() * search.size());
        List<InventoryBalanceResponse> content = jdbc.query("""
                SELECT b.id AS balance_id, w.id AS warehouse_id, w.code AS warehouse_code,
                       i.id AS ingredient_id, i.sku AS ingredient_sku, i.name AS ingredient_name,
                       i.base_unit, i.is_active AS ingredient_active,
                       b.quantity, b.reserved_quantity, b.min_quantity, b.version, b.created_at, b.updated_at
                """ + parts.sql() + order + " LIMIT :limit OFFSET :offset", parameters,
                (rs, rowNum) -> map(rs));
        Long count = jdbc.queryForObject("SELECT count(*) " + parts.sql(), parts.parameters(), Long.class);
        return new PageResult(content, count == null ? 0 : count);
    }

    private static QueryParts filters(String base, UUID restaurantId, UUID warehouseId,
            InventoryBalanceSearch search, boolean optionalWarehouse) {
        StringBuilder sql = new StringBuilder(base);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("restaurantId", restaurantId);
        if (!optionalWarehouse || warehouseId != null) {
            sql.append(" AND w.id = :warehouseId");
            parameters.put("warehouseId", warehouseId);
        }
        if (search.ingredientActive() != null) {
            sql.append(" AND i.is_active = :ingredientActive");
            parameters.put("ingredientActive", search.ingredientActive());
        }
        if (search.q() != null && !search.q().isBlank()) {
            sql.append(" AND (lower(i.name) LIKE :q ESCAPE '\\\\' OR lower(coalesce(i.sku,'')) LIKE :q ESCAPE '\\\\')");
            parameters.put("q", "%" + escape(search.q().trim().toLowerCase()) + "%");
        }
        return new QueryParts(sql.toString(), parameters);
    }

    private static String order(String sortBy, String direction) {
        if (!SORTS.contains(sortBy))
            throw invalidSearch("Unsupported sortBy");
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction))
            throw invalidSearch("Unsupported direction");
        return " ORDER BY " + SORT_COLUMNS.get(sortBy) + " " + direction.toUpperCase()
                + " NULLS LAST, i.id ASC";
    }

    private static InventoryBalanceResponse map(ResultSet rs) throws SQLException {
        UUID balanceId = rs.getObject("balance_id", UUID.class);
        BigDecimal quantity = rs.getBigDecimal("quantity").setScale(3);
        BigDecimal reserved = rs.getBigDecimal("reserved_quantity").setScale(3);
        BigDecimal min = rs.getBigDecimal("min_quantity").setScale(3);
        Long version = balanceId == null ? null : rs.getLong("version");
        return new InventoryBalanceResponse(
                balanceId,
                balanceId != null,
                rs.getObject("warehouse_id", UUID.class),
                rs.getString("warehouse_code"),
                rs.getObject("ingredient_id", UUID.class),
                rs.getString("ingredient_sku"),
                rs.getString("ingredient_name"),
                rs.getString("base_unit"),
                rs.getBoolean("ingredient_active"),
                quantity,
                reserved,
                quantity.subtract(reserved),
                min,
                min.signum() > 0 && quantity.compareTo(min) <= 0,
                version,
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BusinessException invalidSearch(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public record PageResult(List<InventoryBalanceResponse> content, long totalElements) {
    }

    private record QueryParts(String sql, Map<String, Object> parameters) {
    }
}
