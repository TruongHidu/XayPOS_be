package com.possaas.modules.subscription.dto;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CurrentEntitlementResponse(
    UUID subscriptionId,
    String packageCode,
    SubscriptionStatus status,
    Instant startAt,
    Instant endAt,
    List<FeatureEntitlementResponse> features
) {}
