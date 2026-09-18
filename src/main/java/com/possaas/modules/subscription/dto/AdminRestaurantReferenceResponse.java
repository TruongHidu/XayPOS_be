package com.possaas.modules.subscription.dto;

import com.possaas.modules.restaurant.entity.RestaurantStatus;
import java.util.UUID;

public record AdminRestaurantReferenceResponse(
    UUID id,
    String code,
    String name,
    RestaurantStatus status
) {}
