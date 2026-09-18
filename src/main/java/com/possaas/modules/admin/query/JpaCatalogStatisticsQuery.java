package com.possaas.modules.admin.query;

import com.possaas.modules.admin.application.port.CatalogStatisticsQuery;
import com.possaas.modules.admin.domain.CatalogStatistics;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaCatalogStatisticsQuery implements CatalogStatisticsQuery {
    private final EntityManager entityManager;

    @Override
    public CatalogStatistics getStatistics() {
        Object[] values = (Object[]) entityManager.createNativeQuery("""
            select (select count(*) from packages),
                   (select count(*) from packages where is_active = true),
                   (select count(*) from packages where is_active = false),
                   (select count(*) from features),
                   (select count(*) from features where is_active = true),
                   (select count(*) from features where is_active = false)
            """).getSingleResult();
        return new CatalogStatistics(
            new CatalogStatistics.StatusCounts(
                number(values[0]),
                number(values[1]),
                number(values[2])
            ),
            new CatalogStatistics.StatusCounts(
                number(values[3]),
                number(values[4]),
                number(values[5])
            )
        );
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
