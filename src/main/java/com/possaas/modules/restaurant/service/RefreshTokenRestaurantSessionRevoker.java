package com.possaas.modules.restaurant.service;

import com.possaas.modules.auth.service.RefreshTokenService;
import com.possaas.modules.restaurant.application.port.RestaurantSessionRevoker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshTokenRestaurantSessionRevoker implements RestaurantSessionRevoker {
    private final RefreshTokenService refreshTokenService;

    @Override
    public int revokeAllActiveForRestaurant(UUID restaurantId) {
        return refreshTokenService.revokeAllActiveForRestaurant(restaurantId);
    }
}
