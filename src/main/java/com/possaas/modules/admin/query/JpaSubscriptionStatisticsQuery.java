package com.possaas.modules.admin.query;

import com.possaas.modules.admin.application.port.SubscriptionStatisticsQuery;
import com.possaas.modules.admin.domain.SubscriptionStatistics;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaSubscriptionStatisticsQuery implements SubscriptionStatisticsQuery {
    private final EntityManager entityManager;

    @Override
    public SubscriptionStatistics getStatistics(Instant now) {
        Object[] values = (Object[]) entityManager.createNativeQuery("""
            select count(*),
                   count(*) filter (where s.status = 'PENDING'),
                   count(*) filter (where s.status = 'ACTIVE'),
                   count(*) filter (
                       where s.status = 'ACTIVE'
                         and s.start_at <= :now
                         and :now < s.end_at
                   ),
                   count(*) filter (
                       where s.status = 'ACTIVE'
                         and s.end_at <= :now
                   ),
                   count(*) filter (where s.status = 'EXPIRED'),
                   count(*) filter (where s.status = 'CANCELLED'),
                   count(*) filter (
                       where s.status = 'ACTIVE'
                         and s.start_at <= :now
                         and :now < s.end_at
                         and s.end_at <= :sevenDaysFromNow
                   )
              from restaurant_subscriptions s
              join restaurants r on r.id = s.restaurant_id
             where r.deleted_at is null
            """)
            .setParameter("now", now)
            .setParameter("sevenDaysFromNow", now.plus(Duration.ofDays(7)))
            .getSingleResult();
        return new SubscriptionStatistics(
            number(values[0]),
            number(values[1]),
            number(values[2]),
            number(values[3]),
            number(values[4]),
            number(values[5]),
            number(values[6]),
            number(values[7])
        );
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
