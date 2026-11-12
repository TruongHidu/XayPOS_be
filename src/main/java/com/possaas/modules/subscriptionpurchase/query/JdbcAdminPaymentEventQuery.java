package com.possaas.modules.subscriptionpurchase.query;

import static com.possaas.modules.subscriptionpurchase.query.AdminPaymentSql.*;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.application.port.AdminPaymentEventQuery;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ReviewCounts;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcAdminPaymentEventQuery implements AdminPaymentEventQuery {
    private static final String SEARCH_FROM = EVENT_FROM + """
        where (cast(:state as varchar) is null or e.state = cast(:state as varchar))
          and (cast(:reason as varchar) is null or e.review_reason = cast(:reason as varchar))
          and (cast(:restaurantId as uuid) is null or e.restaurant_id = cast(:restaurantId as uuid))
          and (cast(:q as varchar) is null or lower(coalesce(e.payment_reference, '')) like cast(:q as varchar)
               or lower(coalesce(e.payload->>'content', '')) like cast(:q as varchar)
               or lower(coalesce(e.bank_reference, '')) like cast(:q as varchar)
               or lower(coalesce(r.code, '')) like cast(:q as varchar) or lower(coalesce(r.name, '')) like cast(:q as varchar))
        """;

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public PageResponse<PaymentEventSummary> search(PaymentEventState state, ReviewReason reason, UUID restaurantId, String q,
            int page, int size) {
        var params = new MapSqlParameterSource()
            .addValue("state", state == null ? null : state.name())
            .addValue("reason", reason == null ? null : reason.name())
            .addValue("restaurantId", restaurantId)
            .addValue("q", containsPattern(q))
            .addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("select count(*) " + SEARCH_FROM, params, Long.class);
        List<PaymentEventSummary> rows = jdbc.query("select " + EVENT_COLUMNS + SEARCH_FROM
            + " order by e.received_at desc, e.id desc limit :limit offset :offset", params, EVENT);
        return page(rows, page, size, total);
    }

    @Override
    public Optional<PaymentEventSummary> find(UUID eventId) {
        return jdbc.query("select " + EVENT_COLUMNS + EVENT_FROM + " where e.id = :id",
            new MapSqlParameterSource("id", eventId), EVENT).stream().findFirst();
    }

    @Override
    public List<PaymentEventSummary> findByPurchase(UUID purchaseId) {
        return jdbc.query("select " + EVENT_COLUMNS + EVENT_FROM + " where e.purchase_id = :id order by e.received_at desc, e.id desc",
            new MapSqlParameterSource("id", purchaseId), EVENT);
    }

    @Override
    public ReviewCounts reviewCounts() {
        return jdbc.queryForObject("""
            select (select count(*) from subscription_payment_events where state = 'REVIEW_REQUIRED') as events,
                   (select count(*) from subscription_purchases where status = 'REVIEW_REQUIRED') as purchases,
                   (select count(*) from subscription_purchases where status = 'PAID') as paid
            """, new MapSqlParameterSource(), (rs, i) -> new ReviewCounts(rs.getLong("events"), rs.getLong("purchases"), rs.getLong("paid")));
    }
}
