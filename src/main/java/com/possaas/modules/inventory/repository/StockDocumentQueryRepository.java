package com.possaas.modules.inventory.repository;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.dto.StockDocumentSearch;
import com.possaas.modules.inventory.dto.StockDocumentSummaryResponse;
import com.possaas.modules.inventory.entity.StockDocumentStatus;
import com.possaas.modules.inventory.entity.StockDocumentType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StockDocumentQueryRepository {
    private static final Set<String> SORTS = Set.of(
            "documentCode", "documentType", "status", "createdAt", "updatedAt", "totalCost");
    private static final Map<String, String> COLUMNS = Map.of(
            "documentCode", "lower(d.document_code)",
            "documentType", "d.document_type",
            "status", "d.status",
            "createdAt", "d.created_at",
            "updatedAt", "d.updated_at",
            "totalCost", "d.total_cost");

    private final NamedParameterJdbcTemplate jdbc;

    public PageResult search(UUID restaurantId, StockDocumentSearch search) {
        StringBuilder from = new StringBuilder("""
                FROM stock_documents d
                JOIN warehouses w ON w.id=d.warehouse_id AND w.restaurant_id=d.restaurant_id
                WHERE d.restaurant_id=:restaurantId
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("restaurantId", restaurantId);
        if (search.q() != null && !search.q().isBlank()) {
            from.append(" AND (lower(d.document_code) LIKE :q ESCAPE '\\\\' OR lower(coalesce(d.note,'')) LIKE :q ESCAPE '\\\\')");
            params.put("q", "%" + escape(search.q().trim().toLowerCase()) + "%");
        }
        if (search.type() != null) { from.append(" AND d.document_type=:type"); params.put("type", search.type().name()); }
        if (search.status() != null) { from.append(" AND d.status=:status"); params.put("status", search.status().name()); }
        if (search.warehouseId() != null) { from.append(" AND d.warehouse_id=:warehouseId"); params.put("warehouseId", search.warehouseId()); }
        if (search.createdFrom() != null) { from.append(" AND d.created_at>=:createdFrom"); params.put("createdFrom", search.createdFrom()); }
        if (search.createdTo() != null) { from.append(" AND d.created_at<=:createdTo"); params.put("createdTo", search.createdTo()); }
        String order = order(search.sortBy(), search.direction());
        Map<String, Object> paged = new HashMap<>(params);
        paged.put("limit", search.size());
        paged.put("offset", (long) search.page() * search.size());
        List<StockDocumentSummaryResponse> content = jdbc.query("""
                SELECT d.id,d.document_code,d.document_type,d.status,d.warehouse_id,
                       w.code AS warehouse_code,w.name AS warehouse_name,d.note,d.line_count,
                       d.total_quantity,d.total_cost,d.created_by,d.version,d.created_at,d.updated_at,d.stock_audit_id,d.currency_code
                """ + from + order + " LIMIT :limit OFFSET :offset", paged, (rs, row) -> map(rs));
        Long count = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        return new PageResult(content, count == null ? 0 : count);
    }

    private static StockDocumentSummaryResponse map(ResultSet rs) throws SQLException {
        return new StockDocumentSummaryResponse(
                rs.getObject("id", UUID.class), rs.getString("document_code"),
                StockDocumentType.valueOf(rs.getString("document_type")),
                StockDocumentStatus.valueOf(rs.getString("status")),
                rs.getObject("warehouse_id", UUID.class), rs.getString("warehouse_code"),
                rs.getString("warehouse_name"), rs.getString("note"), rs.getInt("line_count"),
                rs.getBigDecimal("total_quantity"), rs.getBigDecimal("total_cost"),
                rs.getObject("created_by", UUID.class), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getObject("stock_audit_id", UUID.class), rs.getString("currency_code"));
    }

    private static String order(String sortBy, String direction) {
        if (!SORTS.contains(sortBy) || !("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction)))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported sort");
        return " ORDER BY " + COLUMNS.get(sortBy) + " " + direction.toUpperCase() + ", d.id ASC";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record PageResult(List<StockDocumentSummaryResponse> content, long totalElements) {}
}
