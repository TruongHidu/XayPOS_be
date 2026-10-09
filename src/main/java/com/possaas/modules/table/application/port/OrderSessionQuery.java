package com.possaas.modules.table.application.port;

import java.util.UUID;

/** Ownership check for order read-side, not live seating eligibility. */
public interface OrderSessionQuery {
    void requireOwned(UUID restaurantId, UUID sessionId);
}
