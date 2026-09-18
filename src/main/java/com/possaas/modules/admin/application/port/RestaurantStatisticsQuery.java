package com.possaas.modules.admin.application.port;

import com.possaas.modules.admin.domain.RestaurantStatistics;
import java.time.Instant;

@FunctionalInterface
public interface RestaurantStatisticsQuery {
    RestaurantStatistics getStatistics(Instant now);
}
