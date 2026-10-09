package com.possaas.modules.table.application.port;

import java.util.UUID;

/** A real downstream usage check is mandatory when order writes are installed. */
public interface TableSessionUsagePolicy {
    void requireCancellable(UUID restaurantId,UUID sessionId);
}
