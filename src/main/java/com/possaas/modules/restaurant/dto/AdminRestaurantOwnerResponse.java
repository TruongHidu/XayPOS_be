package com.possaas.modules.restaurant.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminRestaurantOwnerResponse(
    UUID id,
    String name,
    String email,
    String phone,
    boolean active,
    Instant lastLoginAt
) {}
