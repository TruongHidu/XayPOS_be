package com.possaas.modules.admin.query;

import com.possaas.modules.admin.application.port.RestaurantStatisticsQuery;
import com.possaas.modules.admin.domain.RestaurantStatistics;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaRestaurantStatisticsQuery implements RestaurantStatisticsQuery {
    private final EntityManager entityManager;

    @Override
    public RestaurantStatistics getStatistics(Instant now) {
        Object[] values = (Object[]) entityManager.createNativeQuery("""
            select count(*),
                   count(*) filter (where r.status = 'ACTIVE'),
                   count(*) filter (where r.status = 'INACTIVE'),
                   count(*) filter (where r.status = 'SUSPENDED'),
                   count(*) filter (where r.created_at >= :lastThirtyDays),
                   count(*) filter (
                       where r.status = 'ACTIVE'
                         and not exists (
                             select 1
                               from restaurant_subscriptions s
                              where s.restaurant_id = r.id
                                and s.status = 'ACTIVE'
                                and s.start_at <= :now
                                and :now < s.end_at
                         )
                   )
              from restaurants r
             where r.deleted_at is null
            """)
            .setParameter("now", now)
            .setParameter("lastThirtyDays", now.minus(java.time.Duration.ofDays(30)))
            .getSingleResult();
        return new RestaurantStatistics(
            number(values[0]),
            number(values[1]),
            number(values[2]),
            number(values[3]),
            number(values[4]),
            number(values[5])
        );
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
