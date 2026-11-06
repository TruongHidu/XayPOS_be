package com.possaas.modules.user.dto;

import java.time.Instant;
import java.util.UUID;

public record AdminRestaurantUserResponse(
    UUID id, String name, String email, String phone, boolean active,
    AdminRoleSummaryResponse role, Instant lastLoginAt, Instant createdAt, Instant updatedAt
) {}
