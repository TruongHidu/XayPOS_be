package com.possaas.modules.restaurant.application.port;

import java.time.Instant;
import java.util.UUID;

/** RESTAURANT-owned menu-credential lifecycle; the credential must never be logged or persisted raw. */
public interface RestaurantMenuGuestAccessLifecycle {
    void invalidateRestaurantCredential(UUID restaurantId, String previousToken, UUID actor, Instant now, String ip);
}
