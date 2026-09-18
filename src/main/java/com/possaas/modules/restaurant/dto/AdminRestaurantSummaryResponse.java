package com.possaas.modules.restaurant.dto;

import com.possaas.modules.restaurant.entity.RestaurantStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminRestaurantSummaryResponse(
    UUID id,
    String code,
    String name,
    String legalName,
    String phone,
    String timezone,
    String currencyCode,
    RestaurantStatus status,
    Instant createdAt,
    Instant updatedAt,
    AdminSubscriptionBriefResponse effectiveSubscription
) {}
