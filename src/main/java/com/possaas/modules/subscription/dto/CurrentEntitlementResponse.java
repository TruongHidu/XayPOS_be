package com.possaas.modules.subscription.dto;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param packageName   current display name of the package, null if the package row no longer exists
 * @param daysRemaining whole days left until {@code endAt}, rounded up (23 hours left = 1); 0 once expired
 */
public record CurrentEntitlementResponse(
    UUID subscriptionId,
    String packageCode,
    String packageName,
    SubscriptionStatus status,
    Instant startAt,
    Instant endAt,
    Long daysRemaining,
    List<FeatureEntitlementResponse> features,
    Long maxStaff
) {}
