package com.possaas.modules.table.application.port;

import java.time.Instant;
import java.util.UUID;

/** TABLE-owned write, joining the order transaction and checking TABLE_OPEN at runtime. */
public interface OrderSeatingCommand {
    UUID open(UUID restaurantId,UUID tableId,short guestCount,Instant now,String ip);
}
