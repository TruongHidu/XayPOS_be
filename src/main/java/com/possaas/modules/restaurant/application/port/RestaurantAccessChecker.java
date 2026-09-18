package com.possaas.modules.restaurant.application.port;

import java.util.UUID;

public interface RestaurantAccessChecker {
    boolean isActive(UUID restaurantId);
}
