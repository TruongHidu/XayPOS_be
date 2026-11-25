package com.possaas.modules.restaurant.application.port;

import com.possaas.modules.restaurant.entity.QrOrderingEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * RESTAURANT-owned hook fired when the owner switches a QR ordering entry point off. The implementation
 * withdraws what guests already hold for that entry point; the caller holds the restaurant lock and transaction.
 */
public interface RestaurantQrOrderingLifecycle {
    void qrOrderingDisabled(UUID restaurantId, QrOrderingEntry entry, UUID actor, Instant now, String ip);
}
