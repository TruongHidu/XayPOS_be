package com.possaas.modules.payment.application.port;

import java.time.Instant;
import java.util.UUID;

/** PAYMENT-owned hook. Only the affected seating scope is cancelled, in the settlement transaction. */
public interface PaymentGuestRequestLifecycle {
    void checkoutStarted(UUID restaurantId, UUID sessionId, UUID actor, Instant now, String ip);
}
