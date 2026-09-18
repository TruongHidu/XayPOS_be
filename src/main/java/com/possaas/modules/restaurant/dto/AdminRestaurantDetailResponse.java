package com.possaas.modules.restaurant.dto;

import com.possaas.modules.restaurant.entity.RestaurantStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminRestaurantDetailResponse(
    UUID id,
    String code,
    String name,
    String legalName,
    String phone,
    String address,
    String timezone,
    String currencyCode,
    RestaurantStatus status,
    Instant createdAt,
    Instant updatedAt,
    List<AdminRestaurantOwnerResponse> owners,
    RestaurantUserCountsResponse userCounts,
    AdminSubscriptionBriefResponse effectiveSubscription,
    AdminSubscriptionBriefResponse latestSubscription
) {
    public AdminRestaurantDetailResponse {
        owners = List.copyOf(owners);
    }
}
