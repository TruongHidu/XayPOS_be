package com.possaas.modules.inventory.repository;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.dto.InventoryTransactionResponse;
import com.possaas.modules.inventory.dto.InventoryTransactionSearch;
import com.possaas.modules.inventory.entity.InventoryTransactionType;
import com.possaas.modules.inventory.entity.InventorySourceType;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryTransactionQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public PageResult search(UUID restaurantId, InventoryTransactionSearch search) {
        StringBuilder from = new StringBuilder("""
                FROM inventory_transactions t
                JOIN warehouses w ON w.id=t.warehouse_id AND w.restaurant_id=t.restaurant_id
                LEFT JOIN stock_document_items si ON si.id=t.stock_document_item_id AND si.restaurant_id=t.restaurant_id
                LEFT JOIN stock_documents d ON d.id=t.stock_document_id AND d.restaurant_id=t.restaurant_id
                LEFT JOIN inventory_reservations r ON r.id=t.reservation_id AND r.restaurant_id=t.restaurant_id
                WHERE t.restaurant_id=:restaurantId
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("restaurantId", restaurantId);
        if (search.warehouseId() != null) { from.append(" AND t.warehouse_id=:warehouseId"); params.put("warehouseId", search.warehouseId()); }
        if (search.ingredientId() != null) { from.append(" AND t.item_id=:ingredientId"); params.put("ingredientId", search.ingredientId()); }
        if (search.documentId() != null) { from.append(" AND t.stock_document_id=:documentId"); params.put("documentId", search.documentId()); }
        if (search.orderId() != null) { from.append(" AND t.order_id=:orderId"); params.put("orderId", search.orderId()); }
        if (search.orderItemId() != null) { from.append(" AND t.order_item_id=:orderItemId"); params.put("orderItemId", search.orderItemId()); }
        if (search.sourceType() != null) { from.append(" AND t.source_type=:sourceType"); params.put("sourceType", search.sourceType().name()); }
        if (search.transactionType() != null) { from.append(" AND t.transaction_type=:transactionType"); params.put("transactionType", search.transactionType().name()); }
        if (search.createdFrom() != null) { from.append(" AND t.created_at>=:createdFrom"); params.put("createdFrom", search.createdFrom()); }
        if (search.createdTo() != null) { from.append(" AND t.created_at<=:createdTo"); params.put("createdTo", search.createdTo()); }
        if (!"createdAt".equals(search.sortBy()) || !("asc".equalsIgnoreCase(search.direction()) || "desc".equalsIgnoreCase(search.direction())))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported sort");
        String order = " ORDER BY t.created_at " + search.direction().toUpperCase() + ", t.id ASC";
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", search.size());
        paged.put("offset", (long) search.page() * search.size());
        List<InventoryTransactionResponse> content = jdbc.query("""
                SELECT t.id,t.warehouse_id,w.code AS warehouse_code,t.item_id,
                       CASE WHEN t.source_type='ORDER_ITEM' THEN r.ingredient_sku_snapshot ELSE si.ingredient_sku END AS sku,
                       CASE WHEN t.source_type='ORDER_ITEM' THEN r.ingredient_name_snapshot ELSE si.ingredient_name END AS name,t.unit,
                       t.stock_document_id,d.document_code,d.document_type,t.transaction_type,
                       t.quantity_change,t.before_quantity,t.after_quantity,t.unit_cost,t.created_by,t.created_at,
                       t.source_type,t.order_id,t.order_item_id,t.reservation_id,t.recipe_usage_id
                """ + from + order + " LIMIT :limit OFFSET :offset", paged, (rs, row) -> map(rs));
        Long count = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        return new PageResult(content, count == null ? 0 : count);
    }

    private static InventoryTransactionResponse map(ResultSet rs) throws SQLException {
        return new InventoryTransactionResponse(
                rs.getObject("id", UUID.class), rs.getObject("warehouse_id", UUID.class),
                rs.getString("warehouse_code"), rs.getObject("item_id", UUID.class),
                rs.getString("sku"), rs.getString("name"), rs.getString("unit"),
                rs.getObject("stock_document_id", UUID.class), rs.getString("document_code"),
                rs.getString("document_type") == null ? null : StockDocumentType.valueOf(rs.getString("document_type")),
                InventoryTransactionType.valueOf(rs.getString("transaction_type")),
                rs.getBigDecimal("quantity_change"), rs.getBigDecimal("before_quantity"),
                rs.getBigDecimal("after_quantity"), rs.getBigDecimal("unit_cost"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                InventorySourceType.valueOf(rs.getString("source_type")), rs.getObject("order_id", UUID.class),
                rs.getObject("order_item_id", UUID.class), rs.getObject("reservation_id", UUID.class),
                rs.getObject("recipe_usage_id", UUID.class));
    }

    public record PageResult(List<InventoryTransactionResponse> content, long totalElements) {}
}
