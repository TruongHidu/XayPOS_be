package com.possaas.modules.subscription.dto;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SubscriptionResponse(
    UUID id,
    UUID restaurantId,
    UUID packageId,
    String packageCode,
    SubscriptionStatus status,
    Instant startAt,
    Instant endAt,
    boolean autoRenew,
    BigDecimal priceAmount,
    String currencyCode,
    Instant activatedAt,
    Instant cancelledAt,
    List<FeatureEntitlementResponse> features
) {}
