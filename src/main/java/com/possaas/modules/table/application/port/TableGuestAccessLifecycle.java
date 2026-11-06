package com.possaas.modules.table.application.port;

import java.time.Instant;
import java.util.UUID;

/** TABLE-owned invalidation contract; the caller holds the restaurant lock and transaction. */
public interface TableGuestAccessLifecycle {
    void invalidateTable(UUID restaurantId, UUID tableId, String reason, UUID actor, Instant now, String ip);
}
