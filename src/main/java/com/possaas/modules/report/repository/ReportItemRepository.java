package com.possaas.modules.report.repository;

import com.possaas.modules.report.dto.TopItemReportResponse;
import com.possaas.modules.report.service.ReportQueryContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReportItemRepository {
    private static final String MATCHING_ITEMS = """
        FROM order_items oi
        JOIN orders o ON o.restaurant_id = oi.restaurant_id AND o.id = oi.order_id
        WHERE oi.restaurant_id = :restaurantId
          AND o.currency_code = :currencyCode
          AND o.status = 'COMPLETED'
          AND o.completed_at >= :fromInclusive
          AND o.completed_at < :toExclusive
          AND oi.status <> 'CANCELLED'
          AND (CAST(:serviceType AS varchar) IS NULL OR o.service_type = :serviceType)
          AND (CAST(:sourceChannel AS varchar) IS NULL OR o.source_channel = :sourceChannel)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public long countGroups(ReportQueryContext context) {
        String sql = """
            SELECT COUNT(*)
            FROM (
                SELECT 1
                %s
                GROUP BY oi.item_id, oi.item_name, oi.unit
            ) grouped_items
            """.formatted(MATCHING_ITEMS);
        return jdbc.queryForObject(sql, parameters(context, 0, 1), Long.class);
    }

    public List<TopItemReportResponse> findPage(
        ReportQueryContext context,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        String sortExpression = switch (sortBy) {
            case "quantity" -> "SUM(oi.quantity)";
            case "itemSalesAmount" -> "SUM(oi.line_total)";
            default -> throw new IllegalArgumentException("Unsupported report item sort");
        };
        String safeDirection = "asc".equals(direction.toLowerCase(Locale.ROOT)) ? "ASC" : "DESC";
        String sql = """
            SELECT oi.item_id,
                   oi.item_name,
                   oi.unit,
                   SUM(oi.quantity) AS quantity,
                   SUM(oi.line_total) AS item_sales_amount,
                   COUNT(DISTINCT oi.order_id) AS completed_order_count
            %s
            GROUP BY oi.item_id, oi.item_name, oi.unit
            ORDER BY %s %s,
                     oi.item_name ASC,
                     oi.unit ASC,
                     oi.item_id ASC NULLS LAST
            LIMIT :limit OFFSET :offset
            """.formatted(MATCHING_ITEMS, sortExpression, safeDirection);
        return jdbc.query(sql, parameters(context, page, size), (rs, row) -> new TopItemReportResponse(
            rs.getObject("item_id", java.util.UUID.class),
            rs.getString("item_name"),
            rs.getString("unit"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("item_sales_amount"),
            rs.getLong("completed_order_count")
        ));
    }

    private static MapSqlParameterSource parameters(ReportQueryContext context, int page, int size) {
        return new MapSqlParameterSource(Map.of(
            "restaurantId", context.restaurantId(),
            "currencyCode", context.currencyCode(),
            "fromInclusive", java.sql.Timestamp.from(context.fromInclusive()),
            "toExclusive", java.sql.Timestamp.from(context.toExclusive()),
            "limit", size,
            "offset", (long) page * size
        )).addValue("serviceType", context.serviceType() == null ? null : context.serviceType().name())
          .addValue("sourceChannel", context.sourceChannel() == null ? null : context.sourceChannel().name());
    }
}
