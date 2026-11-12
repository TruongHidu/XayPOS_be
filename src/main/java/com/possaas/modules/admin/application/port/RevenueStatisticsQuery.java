package com.possaas.modules.admin.application.port;

import com.possaas.modules.admin.dto.AdminRevenueResponse.ExpiringRestaurant;
import com.possaas.modules.admin.dto.AdminRevenueResponse.PackageMix;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Read port for SaaS revenue figures. Revenue is the price of each subscription in the month it was activated;
 * a subscription is "online" when it was created by a paid package purchase.
 */
public interface RevenueStatisticsQuery {
    List<MonthlyRevenue> monthlyRevenue(Instant from, Instant to, ZoneId zone);

    List<MonthlyCount> newRestaurantsByMonth(Instant from, Instant to, ZoneId zone);

    RecurringRevenue recurringRevenue(Instant now);

    List<PackageMix> packageMix(Instant now);

    /** Restaurants whose last subscription ended inside (from, now] and that have nothing effective now. */
    long churnedRestaurants(Instant from, Instant now);

    List<ExpiringRestaurant> expiringRestaurants(Instant now, Instant until, int limit);

    /** month is formatted YYYY-MM in the billing zone. */
    record MonthlyRevenue(String month, BigDecimal revenue, BigDecimal onlineRevenue, long activatedSubscriptions) {}

    record MonthlyCount(String month, long count) {}

    record RecurringRevenue(BigDecimal mrr, long payingRestaurants) {}
}
