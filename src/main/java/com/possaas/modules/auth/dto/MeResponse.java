package com.possaas.modules.auth.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record MeResponse(
    UUID id,
    UUID restaurantId,
    String restaurantCode,
    String restaurantName,
    String name,
    String email,
    String phone,
    String role,
    Set<String> permissions,
    Instant lastLoginAt
) {}
