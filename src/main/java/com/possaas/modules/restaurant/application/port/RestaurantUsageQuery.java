package com.possaas.modules.restaurant.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read port for operational counters of one restaurant. */
public interface RestaurantUsageQuery {
    Counters count(UUID restaurantId, Instant since);

    record Counters(long activeStaff, long totalUsers, long activeTables, long activeMenuItems, long orders,
        BigDecimal completedSales, Instant lastOrderAt, Instant lastLoginAt) {}
}
