package com.possaas.modules.subscriptionpurchase.query;

import static com.possaas.modules.subscriptionpurchase.query.AdminPaymentSql.*;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.application.port.AdminPaymentEventQuery;
import com.possaas.modules.subscriptionpurchase.application.port.AdminPurchaseQuery;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.AttemptView;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseDetail;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PurchaseSummary;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcAdminPurchaseQuery implements AdminPurchaseQuery {
    private static final String SEARCH_FROM = """
        from subscription_purchases p join restaurants r on r.id = p.restaurant_id
        where (cast(:status as varchar) is null or p.status = cast(:status as varchar))
          and (cast(:restaurantId as uuid) is null or p.restaurant_id = cast(:restaurantId as uuid))
          and (cast(:q as varchar) is null or lower(r.code) like cast(:q as varchar) or lower(r.name) like cast(:q as varchar)
               or lower(p.quote->>'packageCode') like cast(:q as varchar))
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AdminPaymentEventQuery events;

    @Override
    public PageResponse<PurchaseSummary> search(PurchaseStatus status, UUID restaurantId, String q, int page, int size) {
        var params = new MapSqlParameterSource()
            .addValue("status", status == null ? null : status.name())
            .addValue("restaurantId", restaurantId)
            .addValue("q", containsPattern(q))
            .addValue("limit", size).addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("select count(*) " + SEARCH_FROM, params, Long.class);
        List<PurchaseSummary> rows = jdbc.query("select " + PURCHASE_COLUMNS + SEARCH_FROM
            + " order by p.created_at desc, p.id desc limit :limit offset :offset", params, PURCHASE);
        return page(rows, page, size, total);
    }

    @Override
    public Optional<PurchaseDetail> findDetail(UUID purchaseId) {
        var params = new MapSqlParameterSource("id", purchaseId);
        return jdbc.query("""
            select %s, u.name as creator_name, u.email as creator_email, p.cancel_reason, p.activation_retry_count
            from subscription_purchases p join restaurants r on r.id = p.restaurant_id
            left join users u on u.id = p.created_by
            where p.id = :id
            """.formatted(PURCHASE_COLUMNS), params, (rs, i) -> new PurchaseDetail(PURCHASE.mapRow(rs, i),
                rs.getString("creator_name"), rs.getString("creator_email"), rs.getString("cancel_reason"),
                rs.getInt("activation_retry_count"), attempts(purchaseId), events.findByPurchase(purchaseId)))
            .stream().findFirst();
    }

    private List<AttemptView> attempts(UUID purchaseId) {
        return jdbc.query("""
            select id, status, reference_code, amount, created_at, expires_at, paid_at, failure_code
            from subscription_payment_attempts where purchase_id = :id order by created_at desc, id desc
            """, new MapSqlParameterSource("id", purchaseId), (rs, i) -> new AttemptView(uuid(rs, "id"), rs.getString("status"),
                rs.getString("reference_code"), rs.getBigDecimal("amount"), instant(rs, "created_at"),
                instant(rs, "expires_at"), instant(rs, "paid_at"), rs.getString("failure_code")));
    }
}
