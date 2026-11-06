package com.possaas.modules.table.application.port;

import java.time.Instant;
import java.util.UUID;

/** TABLE-owned closure, joining the ORDER transaction and checking TABLE_CLOSE at runtime. */
public interface OrderSessionClosureCommand {
    /** Requires access to the exact tenant session; rejects statuses other than OPEN/CLOSED. */
    boolean isClosed(UUID restaurantId, UUID sessionId);

    /** Closes only this session after ORDER has made its completion visible in the transaction. */
    void close(UUID restaurantId, UUID sessionId, Instant now, String ip);
}
