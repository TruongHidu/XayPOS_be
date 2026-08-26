package com.possaas.modules.auth.dto;

import java.util.Set;
import java.util.UUID;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn,
    UserSummary user
) {
    public record UserSummary(
        UUID id,
        UUID restaurantId,
        String restaurantCode,
        String restaurantName,
        String name,
        String email,
        String phone,
        String role,
        Set<String> permissions
    ) {}
}
