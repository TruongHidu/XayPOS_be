package com.possaas.modules.report.repository;

import com.possaas.modules.payment.entity.PaymentMethod;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.dto.ReportOverviewResponse.CollectionsMetrics;
import com.possaas.modules.report.service.ReportQueryContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReportOverviewRepository {
    private static final String ORDER_DIMENSIONS = """
        o.restaurant_id = :restaurantId
        AND o.currency_code = :currencyCode
        AND (CAST(:serviceType AS varchar) IS NULL OR o.service_type = :serviceType)
        AND (CAST(:sourceChannel AS varchar) IS NULL OR o.source_channel = :sourceChannel)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public CollectionsMetrics collections(ReportQueryContext context) {
        String sql = """
            SELECT COALESCE(SUM(p.amount), 0) AS collected_amount,
                   COUNT(*) AS payment_count,
                   COUNT(DISTINCT p.order_id) AS paid_order_count
            FROM payments p
            JOIN orders o ON o.restaurant_id = p.restaurant_id AND o.id = p.order_id
            WHERE p.restaurant_id = :restaurantId
              AND p.currency_code = :currencyCode
              AND p.status = 'SUCCESS'
              AND p.paid_at >= :fromInclusive
              AND p.paid_at < :toExclusive
              AND """ + " " + ORDER_DIMENSIONS;
        return jdbc.queryForObject(sql, parameters(context), (rs, row) -> new CollectionsMetrics(
            rs.getBigDecimal("collected_amount"),
            rs.getLong("payment_count"),
            rs.getLong("paid_order_count")
        ));
    }

    public long createdOrderCount(ReportQueryContext context) {
        String sql = """
            SELECT COUNT(*)
            FROM orders o
            WHERE """ + " " + ORDER_DIMENSIONS + """
              AND o.created_at >= :fromInclusive
              AND o.created_at < :toExclusive
            """;
        return jdbc.queryForObject(sql, parameters(context), Long.class);
    }

    public CompletedOrderMetrics completedOrders(ReportQueryContext context) {
        String sql = """
            SELECT COUNT(*) AS order_count,
                   COALESCE(SUM(o.subtotal_amount), 0) AS subtotal_amount,
                   COALESCE(SUM(o.discount_amount), 0) AS discount_amount,
                   COALESCE(SUM(o.tax_amount), 0) AS tax_amount,
                   COALESCE(SUM(o.service_charge_amount), 0) AS service_charge_amount,
                   COALESCE(SUM(o.total_amount), 0) AS sales_amount
            FROM orders o
            WHERE """ + " " + ORDER_DIMENSIONS + """
              AND o.status = 'COMPLETED'
              AND o.completed_at >= :fromInclusive
              AND o.completed_at < :toExclusive
            """;
        return jdbc.queryForObject(sql, parameters(context), (rs, row) -> new CompletedOrderMetrics(
            rs.getLong("order_count"),
            rs.getBigDecimal("subtotal_amount"),
            rs.getBigDecimal("discount_amount"),
            rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("service_charge_amount"),
            rs.getBigDecimal("sales_amount")
        ));
    }

    public long cancelledOrderCount(ReportQueryContext context) {
        String sql = """
            SELECT COUNT(*)
            FROM orders o
            WHERE """ + " " + ORDER_DIMENSIONS + """
              AND o.status = 'CANCELLED'
              AND o.cancelled_at >= :fromInclusive
              AND o.cancelled_at < :toExclusive
            """;
        return jdbc.queryForObject(sql, parameters(context), Long.class);
    }

    public List<CollectedBucket> collectedSeries(
        ReportQueryContext context,
        ReportGranularity granularity
    ) {
        String bucket = granularity == ReportGranularity.MONTH
            ? "CAST(date_trunc('month', timezone(:timezone, p.paid_at)) AS date)"
            : "CAST(timezone(:timezone, p.paid_at) AS date)";
        String sql = """
            SELECT %s AS bucket_start_date,
                   COALESCE(SUM(p.amount), 0) AS collected_amount,
                   COUNT(*) AS payment_count
            FROM payments p
            JOIN orders o ON o.restaurant_id = p.restaurant_id AND o.id = p.order_id
            WHERE p.restaurant_id = :restaurantId
              AND p.currency_code = :currencyCode
              AND p.status = 'SUCCESS'
              AND p.paid_at >= :fromInclusive
              AND p.paid_at < :toExclusive
              AND %s
            GROUP BY bucket_start_date
            ORDER BY bucket_start_date
            """.formatted(bucket, ORDER_DIMENSIONS);
        return jdbc.query(sql, parameters(context), (rs, row) -> new CollectedBucket(
            rs.getObject("bucket_start_date", LocalDate.class),
            rs.getBigDecimal("collected_amount"),
            rs.getLong("payment_count")
        ));
    }

    public List<CompletedBucket> completedSeries(
        ReportQueryContext context,
        ReportGranularity granularity
    ) {
        String bucket = granularity == ReportGranularity.MONTH
            ? "CAST(date_trunc('month', timezone(:timezone, o.completed_at)) AS date)"
            : "CAST(timezone(:timezone, o.completed_at) AS date)";
        String sql = """
            SELECT %s AS bucket_start_date,
                   COALESCE(SUM(o.total_amount), 0) AS sales_amount,
                   COUNT(*) AS order_count
            FROM orders o
            WHERE %s
              AND o.status = 'COMPLETED'
              AND o.completed_at >= :fromInclusive
              AND o.completed_at < :toExclusive
            GROUP BY bucket_start_date
            ORDER BY bucket_start_date
            """.formatted(bucket, ORDER_DIMENSIONS);
        return jdbc.query(sql, parameters(context), (rs, row) -> new CompletedBucket(
            rs.getObject("bucket_start_date", LocalDate.class),
            rs.getBigDecimal("sales_amount"),
            rs.getLong("order_count")
        ));
    }

    public List<PaymentMethodAggregate> paymentMethods(ReportQueryContext context) {
        String sql = """
            SELECT p.payment_method,
                   COALESCE(SUM(p.amount), 0) AS collected_amount,
                   COUNT(*) AS payment_count
            FROM payments p
            JOIN orders o ON o.restaurant_id = p.restaurant_id AND o.id = p.order_id
            WHERE p.restaurant_id = :restaurantId
              AND p.currency_code = :currencyCode
              AND p.status = 'SUCCESS'
              AND p.paid_at >= :fromInclusive
              AND p.paid_at < :toExclusive
              AND """ + " " + ORDER_DIMENSIONS + """
            GROUP BY p.payment_method
            ORDER BY p.payment_method
            """;
        return jdbc.query(sql, parameters(context), (rs, row) -> new PaymentMethodAggregate(
            PaymentMethod.valueOf(rs.getString("payment_method")),
            rs.getBigDecimal("collected_amount"),
            rs.getLong("payment_count")
        ));
    }

    private static MapSqlParameterSource parameters(ReportQueryContext context) {
        return new MapSqlParameterSource(Map.of(
            "restaurantId", context.restaurantId(),
            "currencyCode", context.currencyCode(),
            "fromInclusive", java.sql.Timestamp.from(context.fromInclusive()),
            "toExclusive", java.sql.Timestamp.from(context.toExclusive()),
            "timezone", context.zone().getId(),
            "serviceType", context.serviceType() == null ? "" : context.serviceType().name(),
            "sourceChannel", context.sourceChannel() == null ? "" : context.sourceChannel().name()
        )).addValue("serviceType", context.serviceType() == null ? null : context.serviceType().name())
          .addValue("sourceChannel", context.sourceChannel() == null ? null : context.sourceChannel().name());
    }

    public record CompletedOrderMetrics(
        long orderCount,
        BigDecimal subtotalAmount,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        BigDecimal serviceChargeAmount,
        BigDecimal salesAmount
    ) {}

    public record CollectedBucket(LocalDate date, BigDecimal amount, long paymentCount) {}
    public record CompletedBucket(LocalDate date, BigDecimal amount, long orderCount) {}
    public record PaymentMethodAggregate(PaymentMethod method, BigDecimal amount, long paymentCount) {}
}
