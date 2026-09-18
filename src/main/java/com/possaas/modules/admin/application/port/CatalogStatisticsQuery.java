package com.possaas.modules.admin.application.port;

import com.possaas.modules.admin.domain.CatalogStatistics;

@FunctionalInterface
public interface CatalogStatisticsQuery {
    CatalogStatistics getStatistics();
}
