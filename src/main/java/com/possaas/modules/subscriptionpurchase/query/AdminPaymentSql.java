package com.possaas.modules.subscriptionpurchase.query;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.RestaurantRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Column lists, row mappers and helpers shared by the admin payment read adapters. */
final class AdminPaymentSql {
    private AdminPaymentSql() {}

    static final String PURCHASE_COLUMNS = """
        p.id, p.status, p.review_reason, p.amount, p.currency_code, p.created_at, p.expires_at, p.paid_at,
        p.activated_at, p.closed_at, p.cancelled_at, p.subscription_id, p.activation_error_code,
        p.quote->>'packageCode' as package_code, p.quote->>'packageName' as package_name,
        r.id as r_id, r.code as r_code, r.name as r_name, r.status as r_status
        """;

    static final String EVENT_COLUMNS = """
        e.id, e.state, e.review_reason, e.direction, e.amount, e.currency_code, e.occurred_at, e.received_at,
        e.payment_reference, e.payload->>'content' as transfer_content, e.bank_reference, e.receiver_account,
        e.receiver_bank, e.source, e.delivery_count, e.purchase_id, e.reviewed_at, e.review_note,
        e.resolution_reference, e.last_error_code, p.status as purchase_status, p.amount as purchase_amount,
        u.name as reviewer_name, r.id as r_id, r.code as r_code, r.name as r_name, r.status as r_status
        """;

    static final String EVENT_FROM = """
        from subscription_payment_events e
        left join subscription_purchases p on p.id = e.purchase_id
        left join restaurants r on r.id = e.restaurant_id
        left join users u on u.id = e.reviewed_by
        """;

    static final RowMapper<RestaurantRef> RESTAURANT = (rs, i) -> rs.getObject("r_id") == null ? null
        : new RestaurantRef(uuid(rs, "r_id"), rs.getString("r_code"), rs.getString("r_name"), rs.getString("r_status"));

    static final RowMapper<PurchaseSummary> PURCHASE = (rs, i) -> new PurchaseSummary(
        uuid(rs, "id"), RESTAURANT.mapRow(rs, i), rs.getString("package_code"), rs.getString("package_name"),
        rs.getBigDecimal("amount"), trim(rs.getString("currency_code")), rs.getString("status"), rs.getString("review_reason"),
        instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "paid_at"), instant(rs, "activated_at"),
        instant(rs, "closed_at"), instant(rs, "cancelled_at"), uuid(rs, "subscription_id"), rs.getString("activation_error_code"));

    static final RowMapper<PaymentEventSummary> EVENT = (rs, i) -> new PaymentEventSummary(
        uuid(rs, "id"), rs.getString("state"), rs.getString("review_reason"), rs.getString("direction"),
        rs.getBigDecimal("amount"), trim(rs.getString("currency_code")), instant(rs, "occurred_at"), instant(rs, "received_at"),
        rs.getString("payment_reference"), rs.getString("transfer_content"), rs.getString("bank_reference"),
        rs.getString("receiver_account"), rs.getString("receiver_bank"), rs.getString("source"), rs.getInt("delivery_count"),
        RESTAURANT.mapRow(rs, i), uuid(rs, "purchase_id"), rs.getString("purchase_status"), rs.getBigDecimal("purchase_amount"),
        instant(rs, "reviewed_at"), rs.getString("reviewer_name"), rs.getString("review_note"),
        rs.getString("resolution_reference"), rs.getString("last_error_code"));

    /** Case-insensitive "contains" pattern with LIKE wildcards escaped (default escape character is backslash). */
    static String containsPattern(String q) {
        if (q == null || q.isBlank()) return null;
        String escaped = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    static <T> PageResponse<T> page(List<T> rows, int page, int size, Long total) {
        long count = total == null ? 0 : total;
        return new PageResponse<>(rows, page, size, count, (int) ((count + size - 1) / size));
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String trim(String value) { return value == null ? null : value.trim(); }
}
