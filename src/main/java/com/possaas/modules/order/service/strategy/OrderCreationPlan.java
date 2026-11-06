package com.possaas.modules.order.service.strategy;

import java.util.UUID;
import java.util.Objects;

/** Read-only decision. A generated session ID is resolved only by the persistence stage. */
public record OrderCreationPlan(Kind kind, UUID tableSessionId, UUID tableId, short guestCount) {
    public enum Kind { NO_SEATING, EXISTING_SESSION, NEW_SESSION }
    public OrderCreationPlan {
        Objects.requireNonNull(kind);
        if(guestCount<1) throw new IllegalArgumentException("Guest count must be positive");
        boolean valid=switch(kind) {
            case NO_SEATING -> tableSessionId==null && tableId==null;
            case EXISTING_SESSION -> tableSessionId!=null && tableId==null;
            case NEW_SESSION -> tableSessionId==null && tableId!=null;
        };
        if(!valid) throw new IllegalArgumentException("Inconsistent order seating plan");
    }
    public OrderCreationPlan(UUID tableSessionId,short guestCount) {
        this(tableSessionId==null?Kind.NO_SEATING:Kind.EXISTING_SESSION,tableSessionId,null,guestCount);
    }
    public static OrderCreationPlan newSession(UUID tableId,short guestCount) {
        return new OrderCreationPlan(Kind.NEW_SESSION,null,tableId,guestCount);
    }
}
