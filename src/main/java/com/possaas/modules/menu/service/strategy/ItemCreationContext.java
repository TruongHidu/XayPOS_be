package com.possaas.modules.menu.service.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Trusted, normalized application input, not an HTTP request. */
public record ItemCreationContext(UUID restaurantId, UUID groupId, String sku, String name, String baseUnit,
        String description, String imageUrl, BigDecimal salePrice, boolean active, Instant now) {
}
