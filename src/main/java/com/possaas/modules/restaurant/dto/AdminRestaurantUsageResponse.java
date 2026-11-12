package com.possaas.modules.restaurant.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** packageCode/maxStaff are null without an effective subscription; maxStaff null with a package means unlimited. */
public record AdminRestaurantUsageResponse(
    String packageCode, Instant subscriptionEndAt, Long maxStaff, long activeStaff, long totalUsers,
    long activeTables, long activeMenuItems, long ordersLast30Days, BigDecimal salesLast30Days,
    Instant lastOrderAt, Instant lastLoginAt, List<String> features
) {}
