package com.possaas.modules.admin.dto;

import com.possaas.modules.admin.domain.CatalogStatistics;
import com.possaas.modules.admin.domain.RestaurantStatistics;
import com.possaas.modules.admin.domain.SubscriptionStatistics;
import java.time.Instant;

public record AdminDashboardSummaryResponse(
    Instant generatedAt,
    RestaurantStatistics restaurants,
    SubscriptionStatistics subscriptions,
    CatalogStatistics.StatusCounts packages,
    CatalogStatistics.StatusCounts features
) {}
