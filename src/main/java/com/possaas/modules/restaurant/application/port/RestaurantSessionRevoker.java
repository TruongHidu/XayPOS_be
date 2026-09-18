package com.possaas.modules.restaurant.application.port;

import java.util.UUID;

@FunctionalInterface
public interface RestaurantSessionRevoker {
    int revokeAllActiveForRestaurant(UUID restaurantId);
}
