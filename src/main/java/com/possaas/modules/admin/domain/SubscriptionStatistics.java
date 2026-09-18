package com.possaas.modules.admin.domain;

public record SubscriptionStatistics(
    long total,
    long pending,
    long activeStatus,
    long effectiveNow,
    long staleActive,
    long expired,
    long cancelled,
    long expiringWithin7Days
) {}
