package com.possaas.modules.restaurant.query;

import com.possaas.modules.restaurant.application.port.RestaurantUsageQuery;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRestaurantUsageQuery implements RestaurantUsageQuery {
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public Counters count(UUID restaurantId, Instant since) {
        return jdbc.queryForObject("""
            select
              (select count(*) from users u join roles r on r.id = u.role_id where u.restaurant_id = :restaurantId
                 and u.deleted_at is null and u.is_active and r.code in ('MANAGER','WAITER','KITCHEN','CASHIER')) as active_staff,
              (select count(*) from users u where u.restaurant_id = :restaurantId and u.deleted_at is null) as total_users,
              (select count(*) from restaurant_tables t where t.restaurant_id = :restaurantId and t.deleted_at is null
                 and t.status <> 'INACTIVE') as active_tables,
              (select count(*) from items i where i.restaurant_id = :restaurantId and i.is_active) as active_items,
              (select count(*) from orders o where o.restaurant_id = :restaurantId and o.created_at >= :since
                 and o.status <> 'CANCELLED') as orders,
              (select coalesce(sum(o.total_amount), 0) from orders o where o.restaurant_id = :restaurantId
                 and o.created_at >= :since and o.status = 'COMPLETED') as sales,
              (select max(o.created_at) from orders o where o.restaurant_id = :restaurantId) as last_order_at,
              (select max(u.last_login_at) from users u where u.restaurant_id = :restaurantId and u.deleted_at is null) as last_login_at
            """, new MapSqlParameterSource("restaurantId", restaurantId).addValue("since", Timestamp.from(since)),
            (rs, i) -> new Counters(rs.getLong("active_staff"), rs.getLong("total_users"), rs.getLong("active_tables"),
                rs.getLong("active_items"), rs.getLong("orders"), money(rs.getBigDecimal("sales")),
                instant(rs.getTimestamp("last_order_at")), instant(rs.getTimestamp("last_login_at"))));
    }

    private static BigDecimal money(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
