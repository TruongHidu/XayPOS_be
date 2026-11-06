package com.possaas.modules.order.application.port;

import java.util.Collection;
import java.util.UUID;

/** ORDER-owned read-only checkout fact, implemented by PAYMENT without exposing provider internals. */
public interface PaymentCheckoutQuery {
    boolean hasPending(UUID restaurantId, Collection<UUID> orderIds);
}
