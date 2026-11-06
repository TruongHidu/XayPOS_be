package com.possaas.modules.subscription.application.port;

import java.time.Instant;
import java.util.UUID;

/** Caller must hold the restaurant write lock, before acquiring any subscription/package lock. */
public interface SubscriptionPurchaseConflictGuard {
    void assertNoOpenPurchase(UUID restaurantId, Instant now);
}
