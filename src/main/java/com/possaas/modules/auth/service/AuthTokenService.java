package com.possaas.modules.auth.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.auth.dto.AuthResponse;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.user.entity.User;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthTokenService {
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthResponse issueTokenPair(
        User user,
        Role role,
        Restaurant restaurant,
        Set<String> permissions,
        String deviceInfo,
        String ipAddress
    ) {
        CurrentUser currentUser = new CurrentUser(
            user.getId(),
            user.getRestaurantId(),
            user.getEmail(),
            role.getCode(),
            permissions
        );
        String accessToken = jwtTokenService.createAccessToken(currentUser);
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(
            user.getId(),
            deviceInfo,
            ipAddress
        );

        AuthResponse.UserSummary userSummary = new AuthResponse.UserSummary(
            user.getId(),
            user.getRestaurantId(),
            restaurant == null ? null : restaurant.getCode(),
            restaurant == null ? null : restaurant.getName(),
            user.getName(),
            user.getEmail(),
            user.getPhone(),
            role.getCode(),
            permissions
        );
        return new AuthResponse(
            accessToken,
            refreshToken.rawToken(),
            "Bearer",
            jwtTokenService.accessTokenExpiresInSeconds(),
            userSummary
        );
    }
}
