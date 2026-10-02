package com.possaas.modules.restaurant.dto;

import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import java.time.Instant;
import java.util.UUID;

public record RestaurantProfileResponse(UUID id, String code, String name, String legalName,
    String phone, String address, String timezone, String currencyCode, RestaurantStatus status,
    Instant createdAt, Instant updatedAt) {
    public static RestaurantProfileResponse from(Restaurant r) {
        return new RestaurantProfileResponse(r.getId(), r.getCode(), r.getName(), r.getLegalName(),
            r.getPhone(), r.getAddress(), r.getTimezone(), r.getCurrencyCode(), r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
