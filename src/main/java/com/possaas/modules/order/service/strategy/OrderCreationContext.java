package com.possaas.modules.order.service.strategy;

import java.time.Instant;
import java.util.UUID;

/** Trusted application context. Staff HTTP adapters supply tenant/actor; future guest adapters can be separate. */
public record OrderCreationContext(UUID restaurantId, UUID actorUserId, String currencyCode,
        UUID tableSessionId, Integer guestCount, Instant now, UUID tableId) {
    public OrderCreationContext(UUID restaurantId, UUID actorUserId, String currencyCode,
            UUID tableSessionId, Integer guestCount, Instant now) {
        this(restaurantId,actorUserId,currencyCode,tableSessionId,guestCount,now,null);
    }
}
