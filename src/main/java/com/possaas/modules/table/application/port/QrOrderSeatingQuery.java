package com.possaas.modules.table.application.port;

import java.util.Optional;
import java.util.UUID;

/** Pure seating observation for a staff review; never authorizes or opens a session. */
public interface QrOrderSeatingQuery {
    Optional<CurrentSession> current(UUID restaurantId, UUID tableId);
    record CurrentSession(UUID sessionId, short guestCount, long version) {}
}
