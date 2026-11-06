package com.possaas.modules.order.application.port;

import java.util.UUID;

public interface QrOrderCheckoutQuery {
    boolean isAccepting(UUID restaurantId, UUID sessionId);
    void requireAccepting(UUID restaurantId, UUID sessionId);
}
