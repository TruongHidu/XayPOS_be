package com.possaas.modules.order.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Order-owned financial transition used by trusted payment application services. */
public interface OrderPaymentCommand {
    void requireOwned(UUID restaurantId, UUID orderId);

    Settlement settleFull(UUID restaurantId, UUID orderId, long expectedVersion,
                          BigDecimal amount, Instant now);

    record Settlement(UUID orderId, BigDecimal amount, String currencyCode, long orderVersion) {}
}
