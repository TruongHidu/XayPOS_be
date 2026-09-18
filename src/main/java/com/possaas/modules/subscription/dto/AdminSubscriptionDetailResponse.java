package com.possaas.modules.subscription.dto;

import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminSubscriptionDetailResponse(
    UUID id,
    AdminRestaurantReferenceResponse restaurant,
    AdminPackageReferenceResponse packageInfo,
    SubscriptionStatus status,
    boolean effective,
    Instant startAt,
    Instant endAt,
    boolean autoRenew,
    BigDecimal priceAmount,
    String currencyCode,
    Instant activatedAt,
    Instant cancelledAt,
    Instant createdAt,
    Instant updatedAt,
    List<FeatureEntitlementResponse> features
) {}
