package com.possaas.modules.admin.service;

import com.possaas.modules.admin.application.port.CatalogStatisticsQuery;
import com.possaas.modules.admin.application.port.RestaurantStatisticsQuery;
import com.possaas.modules.admin.application.port.SubscriptionStatisticsQuery;
import com.possaas.modules.admin.domain.CatalogStatistics;
import com.possaas.modules.admin.dto.AdminDashboardSummaryResponse;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminDashboardQueryService {
    private final RestaurantStatisticsQuery restaurantStatisticsQuery;
    private final SubscriptionStatisticsQuery subscriptionStatisticsQuery;
    private final CatalogStatisticsQuery catalogStatisticsQuery;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdminDashboardSummaryResponse getSummary() {
        Instant generatedAt = clock.instant();
        CatalogStatistics catalog = catalogStatisticsQuery.getStatistics();
        return new AdminDashboardSummaryResponse(
            generatedAt,
            restaurantStatisticsQuery.getStatistics(generatedAt),
            subscriptionStatisticsQuery.getStatistics(generatedAt),
            catalog.packages(),
            catalog.features()
        );
    }
}
