package com.possaas.modules.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SaaS revenue overview. Revenue is the package price of each subscription, recognised in the month it was
 * activated (Asia/Ho_Chi_Minh); "online" subscriptions came from a paid package purchase, "manual" ones were
 * created by an admin. MRR normalises each effective subscription's price to a 30.44-day month.
 */
public record AdminRevenueResponse(
    Instant generatedAt,
    String currencyCode,
    String zoneId,
    BigDecimal mrr,
    long payingRestaurants,
    BigDecimal revenueThisMonth,
    BigDecimal revenuePreviousMonth,
    long churnedLast30Days,
    List<MonthPoint> series,
    List<PackageMix> packageMix,
    List<ExpiringRestaurant> expiringSoon
) {
    public record MonthPoint(String month, BigDecimal revenue, BigDecimal onlineRevenue, BigDecimal manualRevenue,
        long activatedSubscriptions, long newRestaurants) {}

    public record PackageMix(String packageCode, String packageName, long restaurants, BigDecimal mrr) {}

    public record ExpiringRestaurant(UUID restaurantId, String restaurantCode, String restaurantName, String packageCode,
        Instant endAt, boolean renewalPending) {}
}
