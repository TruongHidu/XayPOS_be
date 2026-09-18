package com.possaas.modules.admin.domain;

public record RestaurantStatistics(
    long total,
    long active,
    long inactive,
    long suspended,
    long newLast30Days,
    long activeWithoutEffectiveSubscription
) {}
