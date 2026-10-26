package com.possaas.infrastructure.realtime;

import java.util.Objects;
import java.util.UUID;

/** Tenant routing is internal and must never be supplied by a WebSocket client. */
public record InventoryAlertChangedEvent(UUID restaurantId, RealtimeEventEnvelope envelope) {
    public InventoryAlertChangedEvent {
        Objects.requireNonNull(restaurantId);
        Objects.requireNonNull(envelope);
    }
}
