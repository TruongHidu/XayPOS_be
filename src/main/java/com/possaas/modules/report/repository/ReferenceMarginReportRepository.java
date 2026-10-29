package com.possaas.modules.report.repository;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.service.ReportQueryContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReferenceMarginReportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // A single shared relation defines the eligible order and line cohort for every endpoint.
    // Neither payment nor recipe usage is joined, so each order is represented once.
    private static final String COHORT = """
        WITH matching_orders AS (
            SELECT o.* FROM orders o
            WHERE o.restaurant_id = :restaurantId AND o.currency_code = :currencyCode
              AND o.status = 'COMPLETED' AND o.completed_at >= :fromInclusive AND o.completed_at < :toExclusive
              AND (CAST(:serviceType AS varchar) IS NULL OR o.service_type = :serviceType)
              AND (CAST(:sourceChannel AS varchar) IS NULL OR o.source_channel = :sourceChannel)
        ), classified AS (
            SELECT o.id AS oid, oi.id AS line_id, s.total_cost_snapshot,
              CASE
                WHEN oi.id IS NULL THEN NULL
                WHEN s.id IS NULL OR s.order_quantity_snapshot IS DISTINCT FROM oi.quantity
                    OR s.order_unit_snapshot IS DISTINCT FROM oi.unit
                    OR s.menu_item_id_snapshot IS DISTINCT FROM oi.item_id THEN 'CONFLICT'
                WHEN s.cost_snapshot_version IS NULL THEN
                    CASE WHEN s.cost_status IS NULL AND s.cost_unavailable_reason IS NULL
                        AND s.unit_cost_snapshot IS NULL AND s.total_cost_snapshot IS NULL
                        THEN 'LEGACY_UNAVAILABLE' ELSE 'CONFLICT' END
                WHEN s.cost_snapshot_version <> 1 THEN 'CONFLICT'
                WHEN s.recipe_enabled_snapshot = false AND s.cost_source_snapshot = 'MANUAL'
                  AND s.snapshot_status = 'RECIPE_DISABLED' AND s.cost_status = 'COMPLETE'
                  AND s.cost_unavailable_reason IS NULL AND s.unit_cost_snapshot >= 0
                  AND s.total_cost_snapshot >= 0 AND s.unit_cost_snapshot <> 'NaN'::numeric
                  AND s.total_cost_snapshot <> 'NaN'::numeric
                  AND s.total_cost_snapshot = round(s.unit_cost_snapshot * s.order_quantity_snapshot, 2) THEN 'COMPLETE'
                WHEN s.recipe_enabled_snapshot = true AND s.cost_source_snapshot = 'RECIPE'
                  AND s.snapshot_status = 'CAPTURED' AND s.cost_status = 'COMPLETE'
                  AND s.cost_unavailable_reason IS NULL AND s.unit_cost_snapshot >= 0
                  AND s.total_cost_snapshot >= 0 AND s.unit_cost_snapshot <> 'NaN'::numeric
                  AND s.total_cost_snapshot <> 'NaN'::numeric
                  AND s.unit_cost_snapshot = round(s.total_cost_snapshot / NULLIF(s.order_quantity_snapshot, 0), 2) THEN 'COMPLETE'
                WHEN s.recipe_enabled_snapshot = false AND s.cost_source_snapshot = 'MANUAL'
                  AND s.snapshot_status = 'RECIPE_DISABLED' AND s.cost_status = 'INCOMPLETE'
                  AND s.cost_unavailable_reason = 'MANUAL_COST_NOT_CONFIGURED'
                  AND s.unit_cost_snapshot IS NULL AND s.total_cost_snapshot IS NULL THEN 'INCOMPLETE'
                WHEN s.recipe_enabled_snapshot = true AND s.cost_source_snapshot = 'RECIPE'
                  AND s.snapshot_status = 'CAPTURED' AND s.cost_status = 'INCOMPLETE'
                  AND s.cost_unavailable_reason = 'INGREDIENT_COST_NOT_CONFIGURED'
                  AND s.unit_cost_snapshot IS NULL AND s.total_cost_snapshot IS NULL THEN 'INCOMPLETE'
                WHEN s.recipe_enabled_snapshot = true AND s.cost_source_snapshot = 'RECIPE'
                  AND s.snapshot_status = 'FEATURE_DISABLED' AND s.cost_status = 'UNAVAILABLE'
                  AND s.cost_unavailable_reason = 'RECIPE_FEATURE_DISABLED'
                  AND s.unit_cost_snapshot IS NULL AND s.total_cost_snapshot IS NULL THEN 'UNAVAILABLE'
                ELSE 'CONFLICT'
              END AS classification
            FROM matching_orders o
            LEFT JOIN order_items oi ON oi.restaurant_id = o.restaurant_id AND oi.order_id = o.id
                AND oi.status <> 'CANCELLED'
            LEFT JOIN order_item_recipe_snapshots s ON s.restaurant_id = oi.restaurant_id AND s.order_item_id = oi.id
        ), line_totals AS (
            SELECT oid, COUNT(line_id) AS eligible,
                COUNT(*) FILTER (WHERE classification = 'COMPLETE') AS costed,
                COUNT(*) FILTER (WHERE classification = 'INCOMPLETE') AS incomplete,
                COUNT(*) FILTER (WHERE classification = 'UNAVAILABLE') AS unavailable,
                COUNT(*) FILTER (WHERE classification = 'LEGACY_UNAVAILABLE') AS legacy,
                COALESCE(SUM(total_cost_snapshot) FILTER (WHERE classification = 'COMPLETE'), 0) AS known_cost
            FROM classified GROUP BY oid
        ), order_cohort AS (
            SELECT o.id, o.order_code, o.completed_at, o.service_type, o.source_channel,
                o.subtotal_amount, o.discount_amount, o.tax_amount, o.service_charge_amount, o.total_amount,
                o.subtotal_amount - o.discount_amount AS net_sales,
                l.eligible, l.costed, l.incomplete, l.unavailable, l.legacy,
                (l.eligible > 0 AND l.costed = l.eligible) AS fully_costed,
                CASE WHEN l.eligible > 0 AND l.costed = l.eligible THEN l.known_cost ELSE NULL END AS reference_cost
            FROM matching_orders o JOIN line_totals l ON l.oid = o.id
        )
        """;

    private static final String AGGREGATES = """
        COUNT(*) AS completed_order_count,
        COUNT(*) FILTER (WHERE fully_costed) AS fully_costed_order_count,
        COALESCE(SUM(subtotal_amount), 0) AS subtotal_amount,
        COALESCE(SUM(discount_amount), 0) AS discount_amount,
        COALESCE(SUM(tax_amount), 0) AS tax_amount,
        COALESCE(SUM(service_charge_amount), 0) AS service_charge_amount,
        COALESCE(SUM(total_amount), 0) AS sales_amount,
        COALESCE(SUM(net_sales) FILTER (WHERE fully_costed), 0) AS covered_net_sales_amount,
        COALESCE(SUM(reference_cost) FILTER (WHERE fully_costed), 0) AS covered_reference_cost_amount,
        COUNT(*) FILTER (WHERE incomplete > 0) AS incomplete_order_count,
        COALESCE(SUM(incomplete), 0) AS incomplete_item_count,
        COUNT(*) FILTER (WHERE unavailable > 0) AS unavailable_order_count,
        COALESCE(SUM(unavailable), 0) AS unavailable_item_count,
        COUNT(*) FILTER (WHERE legacy > 0) AS legacy_order_count,
        COALESCE(SUM(legacy), 0) AS legacy_item_count,
        COUNT(*) FILTER (WHERE eligible = 0) AS no_eligible_order_count
        """;

    public void assertConsistent(ReportQueryContext context) {
        Boolean bad = jdbc.queryForObject(COHORT + "SELECT EXISTS (SELECT 1 FROM classified WHERE classification = 'CONFLICT')", parameters(context), Boolean.class);
        if (Boolean.TRUE.equals(bad)) throw new ConflictException("REPORT_COST_DATA_CONFLICT", "Stored order cost snapshot is inconsistent");
    }

    public Aggregate aggregate(ReportQueryContext context) {
        return jdbc.queryForObject(COHORT + "SELECT " + AGGREGATES + " FROM order_cohort", parameters(context), (rs, n) -> aggregateRow(rs));
    }

    public List<Bucket> buckets(ReportQueryContext context, ReportGranularity granularity) {
        String bucket = granularity == ReportGranularity.MONTH
            ? "CAST(date_trunc('month', timezone(:timezone, completed_at)) AS date)"
            : "CAST(timezone(:timezone, completed_at) AS date)";
        String sql = COHORT + "SELECT " + bucket + " AS bucket_date, " + AGGREGATES
            + " FROM order_cohort GROUP BY bucket_date ORDER BY bucket_date";
        return jdbc.query(sql, parameters(context), (rs, n) -> new Bucket(rs.getObject("bucket_date", LocalDate.class), aggregateRow(rs)));
    }

    public long count(ReportQueryContext context, String coverage) {
        return jdbc.queryForObject(COHORT + "SELECT COUNT(*) FROM order_cohort WHERE " + coveragePredicate(coverage), parameters(context), Long.class);
    }

    public List<OrderRow> page(ReportQueryContext context, String coverage, int page, int size, String sortBy, String direction) {
        String sort = switch (sortBy) {
            case "completedAt" -> "completed_at";
            case "netSalesAmount" -> "net_sales";
            case "referenceCostAmount" -> "reference_cost";
            case "referenceMarginAmount" -> "(net_sales - reference_cost)";
            default -> throw new IllegalArgumentException("Unsupported report sort");
        };
        String order = switch (direction.toLowerCase(Locale.ROOT)) {
            case "asc" -> "ASC";
            case "desc" -> "DESC";
            default -> throw new IllegalArgumentException("Unsupported report direction");
        };
        var params = parameters(context).addValue("limit", size).addValue("offset", (long) page * size);
        String sql = COHORT + "SELECT * FROM order_cohort WHERE " + coveragePredicate(coverage)
            + " ORDER BY " + sort + " " + order + " NULLS LAST, id ASC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, n) -> new OrderRow(
            rs.getObject("id", UUID.class), rs.getString("order_code"), rs.getTimestamp("completed_at").toInstant(),
            ServiceType.valueOf(rs.getString("service_type")), SourceChannel.valueOf(rs.getString("source_channel")),
            rs.getBigDecimal("subtotal_amount"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("service_charge_amount"), rs.getBigDecimal("total_amount"),
            rs.getLong("eligible"), rs.getLong("costed"), rs.getLong("incomplete"),
            rs.getLong("unavailable"), rs.getLong("legacy"), rs.getBigDecimal("reference_cost")));
    }

    private static String coveragePredicate(String coverage) {
        return switch (coverage) {
            case "ALL" -> "TRUE";
            case "COMPLETE" -> "fully_costed";
            case "EXCLUDED" -> "NOT fully_costed";
            default -> throw new IllegalArgumentException("Unsupported cost coverage");
        };
    }

    private static MapSqlParameterSource parameters(ReportQueryContext context) {
        return new MapSqlParameterSource()
            .addValue("restaurantId", context.restaurantId()).addValue("currencyCode", context.currencyCode())
            .addValue("fromInclusive", Timestamp.from(context.fromInclusive())).addValue("toExclusive", Timestamp.from(context.toExclusive()))
            .addValue("timezone", context.zone().getId())
            .addValue("serviceType", context.serviceType() == null ? null : context.serviceType().name())
            .addValue("sourceChannel", context.sourceChannel() == null ? null : context.sourceChannel().name());
    }

    private static Aggregate aggregateRow(ResultSet rs) throws SQLException {
        return new Aggregate(rs.getLong("completed_order_count"), rs.getLong("fully_costed_order_count"),
            rs.getBigDecimal("subtotal_amount"), rs.getBigDecimal("discount_amount"), rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("service_charge_amount"), rs.getBigDecimal("sales_amount"),
            rs.getBigDecimal("covered_net_sales_amount"), rs.getBigDecimal("covered_reference_cost_amount"),
            rs.getLong("incomplete_order_count"), rs.getLong("incomplete_item_count"),
            rs.getLong("unavailable_order_count"), rs.getLong("unavailable_item_count"),
            rs.getLong("legacy_order_count"), rs.getLong("legacy_item_count"), rs.getLong("no_eligible_order_count"));
    }

    public record Aggregate(long completedOrderCount, long fullyCostedOrderCount,
        BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal serviceChargeAmount,
        BigDecimal salesAmount, BigDecimal coveredNetSalesAmount, BigDecimal coveredReferenceCostAmount,
        long incompleteOrderCount, long incompleteItemCount, long unavailableOrderCount, long unavailableItemCount,
        long legacyOrderCount, long legacyItemCount, long noEligibleOrderCount) {}
    public record Bucket(LocalDate date, Aggregate aggregate) {}
    public record OrderRow(UUID orderId, String orderCode, Instant completedAt, ServiceType serviceType,
        SourceChannel sourceChannel, BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal taxAmount,
        BigDecimal serviceChargeAmount, BigDecimal salesAmount, long eligibleItemCount, long fullyCostedItemCount,
        long incompleteItemCount, long unavailableItemCount, long legacyItemCount, BigDecimal referenceCostAmount) {}
}
