package com.possaas.modules.qrorder.event;

import com.possaas.modules.qrorder.entity.QrEntryType;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record QrOrderRequestChanged(UUID eventId, UUID restaurantId, UUID requestId, long version,
        Instant occurredAt, QrRequestStatus status, Change changeKind, QrEntryType entryType,
        UUID tableId, UUID observedSessionId, UUID approvedOrderId, UUID approvedSessionId) {
    public enum Change { CREATED, APPROVED, REJECTED, CANCELLED }
    public QrOrderRequestChanged {
        Objects.requireNonNull(eventId); Objects.requireNonNull(restaurantId); Objects.requireNonNull(requestId);
        Objects.requireNonNull(occurredAt); Objects.requireNonNull(status); Objects.requireNonNull(changeKind);
        Objects.requireNonNull(entryType);
    }
}
