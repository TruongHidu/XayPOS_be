package com.possaas.modules.restaurant.dto;

import java.util.UUID;

/** Internal resolved context; never returned directly from a public controller. */
public record PublicRestaurantMenuContext(UUID restaurantId, String restaurantName, String currencyCode) {}
