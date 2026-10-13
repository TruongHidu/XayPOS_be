package com.possaas.modules.table.application.port;

import java.util.UUID;

/** ORDER decides whether all orders attached to this session permit closing. */
public interface TableSessionClosureReadiness {
    void requireClosable(UUID restaurantId,UUID sessionId);
}
