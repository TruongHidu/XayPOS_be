package com.possaas.modules.table.application.port;

import java.util.UUID;
import java.util.Objects;

public interface OrderSeatingQuery {
    Seating requireOpen(UUID restaurantId,UUID sessionId);
    TableTarget resolveTable(UUID restaurantId,UUID tableId);
    record Seating(UUID sessionId,short guestCount) {
        public Seating {
            Objects.requireNonNull(sessionId);
            if(guestCount<1) throw new IllegalArgumentException("Guest count must be positive");
        }
    }
    sealed interface TableTarget permits Existing,Unoccupied {}
    record Existing(Seating session) implements TableTarget {
        public Existing { Objects.requireNonNull(session); }
    }
    record Unoccupied(UUID tableId) implements TableTarget {
        public Unoccupied { Objects.requireNonNull(tableId); }
    }
}
