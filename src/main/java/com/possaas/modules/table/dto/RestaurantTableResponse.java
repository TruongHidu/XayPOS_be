package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.TableStatus;
import java.time.Instant;
import java.util.UUID;

public record RestaurantTableResponse(UUID id, String code, String name, short capacity, int displayOrder,
        TableStatus status, Area area, boolean occupied, Session currentSession, boolean canOpen,
        long version, Instant createdAt, Instant updatedAt) {
    public record Area(UUID id, String name, boolean active) {}
    public record Session(UUID id, String sessionCode, short guestCount, Instant openedAt, long version) {}
}
