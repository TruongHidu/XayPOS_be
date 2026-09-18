package com.possaas.modules.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.possaas.modules.admin.domain.CatalogStatistics;
import com.possaas.modules.admin.domain.RestaurantStatistics;
import com.possaas.modules.admin.domain.SubscriptionStatistics;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdminDashboardQueryServiceTest {
    @Test
    void oneFixedInstantIsUsedForEveryDashboardStatistic() {
        Instant fixedNow = Instant.parse("2026-08-27T00:00:00Z");
        AtomicReference<Instant> restaurantNow = new AtomicReference<>();
        AtomicReference<Instant> subscriptionNow = new AtomicReference<>();
        RestaurantStatistics restaurantStatistics = new RestaurantStatistics(1, 1, 0, 0, 1, 0);
        SubscriptionStatistics subscriptionStatistics = new SubscriptionStatistics(
            1, 0, 1, 1, 0, 0, 0, 1
        );
        CatalogStatistics catalogStatistics = new CatalogStatistics(
            new CatalogStatistics.StatusCounts(3, 3, 0),
            new CatalogStatistics.StatusCounts(23, 23, 0)
        );
        AdminDashboardQueryService service = new AdminDashboardQueryService(
            now -> {
                restaurantNow.set(now);
                return restaurantStatistics;
            },
            now -> {
                subscriptionNow.set(now);
                return subscriptionStatistics;
            },
            () -> catalogStatistics,
            Clock.fixed(fixedNow, ZoneOffset.UTC)
        );

        var response = service.getSummary();

        assertThat(response.generatedAt()).isEqualTo(fixedNow);
        assertThat(restaurantNow).hasValue(fixedNow);
        assertThat(subscriptionNow).hasValue(fixedNow);
        assertThat(response.restaurants()).isEqualTo(restaurantStatistics);
        assertThat(response.subscriptions()).isEqualTo(subscriptionStatistics);
    }
}
