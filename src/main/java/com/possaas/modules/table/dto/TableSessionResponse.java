package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.TableSessionStatus;
import java.time.Instant;
import java.util.UUID;

public record TableSessionResponse(UUID id, String sessionCode, UUID tableId, String tableCode, String tableName,
        TableSessionStatus status, short guestCount, String note, UUID openedBy, Instant openedAt,
        UUID closedBy, Instant closedAt, String cancelReason, long version, Instant createdAt, Instant updatedAt) {}
