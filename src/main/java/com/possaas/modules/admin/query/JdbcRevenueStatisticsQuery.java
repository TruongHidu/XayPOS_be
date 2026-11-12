package com.possaas.modules.admin.query;

import com.possaas.modules.admin.application.port.RevenueStatisticsQuery;
import com.possaas.modules.admin.dto.AdminRevenueResponse.ExpiringRestaurant;
import com.possaas.modules.admin.dto.AdminRevenueResponse.PackageMix;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcRevenueStatisticsQuery implements RevenueStatisticsQuery {
    /** Price of one subscription period expressed per average month (30.4375 days). */
    private static final String MONTHLY_PRICE =
        "s.price_amount / greatest(1, round(extract(epoch from (s.end_at - s.start_at)) / 86400.0 / 30.4375))";
    private static final String EFFECTIVE =
        "s.status = 'ACTIVE' and s.start_at <= :now and :now < s.end_at and r.deleted_at is null";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<MonthlyRevenue> monthlyRevenue(Instant from, Instant to, ZoneId zone) {
        return jdbc.query("""
            select to_char(date_trunc('month', s.activated_at at time zone :zone), 'YYYY-MM') as month,
                   coalesce(sum(s.price_amount), 0) as revenue,
                   coalesce(sum(s.price_amount) filter (where p.id is not null), 0) as online,
                   count(*) as activated
            from restaurant_subscriptions s
            join restaurants r on r.id = s.restaurant_id
            left join subscription_purchases p on p.subscription_id = s.id
            where s.activated_at is not null and s.activated_at >= :from and s.activated_at <= :to
            group by 1
            """, range(from, to, zone), (rs, i) -> new MonthlyRevenue(rs.getString("month"), rs.getBigDecimal("revenue"),
                rs.getBigDecimal("online"), rs.getLong("activated")));
    }

    @Override
    public List<MonthlyCount> newRestaurantsByMonth(Instant from, Instant to, ZoneId zone) {
        return jdbc.query("""
            select to_char(date_trunc('month', created_at at time zone :zone), 'YYYY-MM') as month, count(*) as total
            from restaurants where deleted_at is null and created_at >= :from and created_at <= :to group by 1
            """, range(from, to, zone), (rs, i) -> new MonthlyCount(rs.getString("month"), rs.getLong("total")));
    }

    @Override
    public RecurringRevenue recurringRevenue(Instant now) {
        return jdbc.queryForObject("""
            select coalesce(sum(%s), 0) as mrr,
                   count(distinct s.restaurant_id) filter (where s.price_amount > 0) as paying
            from restaurant_subscriptions s join restaurants r on r.id = s.restaurant_id
            where %s
            """.formatted(MONTHLY_PRICE, EFFECTIVE), at(now),
            (rs, i) -> new RecurringRevenue(rs.getBigDecimal("mrr"), rs.getLong("paying")));
    }

    @Override
    public List<PackageMix> packageMix(Instant now) {
        return jdbc.query("""
            select pk.code, pk.name, count(distinct s.restaurant_id) as restaurants, coalesce(sum(%s), 0) as mrr
            from restaurant_subscriptions s join restaurants r on r.id = s.restaurant_id
            join packages pk on pk.id = s.package_id
            where %s
            group by pk.code, pk.name order by restaurants desc, pk.code
            """.formatted(MONTHLY_PRICE, EFFECTIVE), at(now), (rs, i) -> new PackageMix(rs.getString("code"),
                rs.getString("name"), rs.getLong("restaurants"), rs.getBigDecimal("mrr")));
    }

    @Override
    public long churnedRestaurants(Instant from, Instant now) {
        Long churned = jdbc.queryForObject("""
            select count(*) from (
                select s.restaurant_id from restaurant_subscriptions s join restaurants r on r.id = s.restaurant_id
                where r.deleted_at is null and s.status in ('ACTIVE', 'EXPIRED')
                group by s.restaurant_id
                having max(s.end_at) > :from and max(s.end_at) <= :now
            ) ended
            """, at(now).addValue("from", Timestamp.from(from)), Long.class);
        return churned == null ? 0 : churned;
    }

    @Override
    public List<ExpiringRestaurant> expiringRestaurants(Instant now, Instant until, int limit) {
        return jdbc.query("""
            select r.id, r.code, r.name, pk.code as package_code, s.end_at,
                   exists (select 1 from restaurant_subscriptions n where n.restaurant_id = s.restaurant_id
                           and n.id <> s.id and n.status in ('PENDING', 'ACTIVE') and n.end_at > s.end_at)
                   or exists (select 1 from subscription_purchases o where o.restaurant_id = s.restaurant_id
                           and o.status in ('PENDING_PAYMENT', 'PAID', 'REVIEW_REQUIRED')) as renewal_pending
            from restaurant_subscriptions s join restaurants r on r.id = s.restaurant_id
            join packages pk on pk.id = s.package_id
            where %s and s.end_at <= :until
            order by s.end_at, r.code limit :limit
            """.formatted(EFFECTIVE), at(now).addValue("until", Timestamp.from(until)).addValue("limit", limit),
            (rs, i) -> new ExpiringRestaurant(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("package_code"), rs.getTimestamp("end_at").toInstant(), rs.getBoolean("renewal_pending")));
    }

    private static MapSqlParameterSource at(Instant now) {
        return new MapSqlParameterSource("now", Timestamp.from(now));
    }

    private static MapSqlParameterSource range(Instant from, Instant to, ZoneId zone) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
            .addValue("zone", zone.getId());
    }
}
