package com.possaas.modules.report.repository;

import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.ReportDateBasis;
import com.possaas.modules.report.dto.ReportOrderRowResponse;
import com.possaas.modules.report.service.ReportQueryContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReportOrderRepository {
    private static final String PAYMENT_CTE = """
        WITH matching_payments AS (
            SELECT p.restaurant_id,
                   p.order_id,
                   SUM(p.amount) AS collected_amount,
                   MAX(p.paid_at) AS last_paid_at
            FROM payments p
            WHERE p.restaurant_id = :restaurantId
              AND p.currency_code = :currencyCode
              AND p.status = 'SUCCESS'
              AND p.paid_at >= :fromInclusive
              AND p.paid_at < :toExclusive
            GROUP BY p.restaurant_id, p.order_id
        )
        """;

    private static final String ORDER_FILTERS = """
        o.restaurant_id = :restaurantId
        AND o.currency_code = :currencyCode
        AND (CAST(:serviceType AS varchar) IS NULL OR o.service_type = :serviceType)
        AND (CAST(:sourceChannel AS varchar) IS NULL OR o.source_channel = :sourceChannel)
        AND (CAST(:status AS varchar) IS NULL OR o.status = :status)
        AND (CAST(:paymentStatus AS varchar) IS NULL OR o.payment_status = :paymentStatus)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public long count(
        ReportQueryContext context,
        ReportDateBasis dateBasis,
        OrderStatus status,
        OrderPaymentStatus paymentStatus
    ) {
        String sql = PAYMENT_CTE + """
            SELECT COUNT(*)
            FROM orders o
            LEFT JOIN matching_payments mp
              ON mp.restaurant_id = o.restaurant_id AND mp.order_id = o.id
            WHERE %s
              AND %s
            """.formatted(ORDER_FILTERS, basisPredicate(dateBasis));
        return jdbc.queryForObject(sql, parameters(context, status, paymentStatus, 0, 1), Long.class);
    }

    public List<ReportOrderRowResponse> findPage(
        ReportQueryContext context,
        ReportDateBasis dateBasis,
        OrderStatus status,
        OrderPaymentStatus paymentStatus,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        String safeSort = switch (sortBy) {
            case "reportEventAt" -> "report_event_at";
            case "totalAmount" -> "o.total_amount";
            default -> throw new IllegalArgumentException("Unsupported report order sort");
        };
        String safeDirection = "asc".equals(direction.toLowerCase(Locale.ROOT)) ? "ASC" : "DESC";
        String eventExpression = eventExpression(dateBasis);
        String stableOrder = "report_event_at".equals(safeSort)
            ? safeSort + " " + safeDirection + ", o.id ASC"
            : safeSort + " " + safeDirection + ", report_event_at " + safeDirection + ", o.id ASC";
        String sql = PAYMENT_CTE + """
            SELECT o.id,
                   o.order_code,
                   o.service_type,
                   o.source_channel,
                   o.status,
                   o.payment_status,
                   o.currency_code,
                   o.total_amount,
                   COALESCE(mp.collected_amount, 0) AS collected_amount_in_range,
                   o.created_at,
                   o.completed_at,
                   o.cancelled_at,
                   %s AS report_event_at
            FROM orders o
            LEFT JOIN matching_payments mp
              ON mp.restaurant_id = o.restaurant_id AND mp.order_id = o.id
            WHERE %s
              AND %s
            ORDER BY %s
            LIMIT :limit OFFSET :offset
            """.formatted(
                eventExpression,
                ORDER_FILTERS,
                basisPredicate(dateBasis),
                stableOrder
            );
        return jdbc.query(
            sql,
            parameters(context, status, paymentStatus, page, size),
            (rs, row) -> new ReportOrderRowResponse(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("order_code"),
                ServiceType.valueOf(rs.getString("service_type")),
                SourceChannel.valueOf(rs.getString("source_channel")),
                OrderStatus.valueOf(rs.getString("status")),
                OrderPaymentStatus.valueOf(rs.getString("payment_status")),
                rs.getString("currency_code").trim(),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("collected_amount_in_range"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("cancelled_at")),
                instant(rs.getTimestamp("report_event_at"))
            )
        );
    }

    private static String basisPredicate(ReportDateBasis basis) {
        return switch (basis) {
            case CREATED -> "o.created_at >= :fromInclusive AND o.created_at < :toExclusive";
            case COMPLETED -> "o.status = 'COMPLETED' AND o.completed_at >= :fromInclusive AND o.completed_at < :toExclusive";
            case CANCELLED -> "o.status = 'CANCELLED' AND o.cancelled_at >= :fromInclusive AND o.cancelled_at < :toExclusive";
            case PAID -> "mp.order_id IS NOT NULL";
        };
    }

    private static String eventExpression(ReportDateBasis basis) {
        return switch (basis) {
            case CREATED -> "o.created_at";
            case COMPLETED -> "o.completed_at";
            case CANCELLED -> "o.cancelled_at";
            case PAID -> "mp.last_paid_at";
        };
    }

    private static MapSqlParameterSource parameters(
        ReportQueryContext context,
        OrderStatus status,
        OrderPaymentStatus paymentStatus,
        int page,
        int size
    ) {
        return new MapSqlParameterSource(Map.of(
            "restaurantId", context.restaurantId(),
            "currencyCode", context.currencyCode(),
            "fromInclusive", java.sql.Timestamp.from(context.fromInclusive()),
            "toExclusive", java.sql.Timestamp.from(context.toExclusive()),
            "limit", size,
            "offset", (long) page * size
        )).addValue("serviceType", context.serviceType() == null ? null : context.serviceType().name())
          .addValue("sourceChannel", context.sourceChannel() == null ? null : context.sourceChannel().name())
          .addValue("status", status == null ? null : status.name())
          .addValue("paymentStatus", paymentStatus == null ? null : paymentStatus.name());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
