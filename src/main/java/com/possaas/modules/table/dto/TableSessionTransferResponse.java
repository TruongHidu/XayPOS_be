package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.TableSessionTransfer;
import java.time.Instant;
import java.util.UUID;

public record TableSessionTransferResponse(UUID id, UUID sessionId, TableSnapshot fromTable,
        TableSnapshot toTable, UUID actorUserId, String reason, long sessionVersionBefore,
        long sessionVersionAfter, Instant transferredAt) {
    public record TableSnapshot(UUID id, String code, String name) {}

    public static TableSessionTransferResponse from(TableSessionTransfer transfer) {
        return new TableSessionTransferResponse(transfer.getId(), transfer.getSessionId(),
                new TableSnapshot(transfer.getFromTableId(), transfer.getFromTableCode(), transfer.getFromTableName()),
                new TableSnapshot(transfer.getToTableId(), transfer.getToTableCode(), transfer.getToTableName()),
                transfer.getActorUserId(), transfer.getReason(), transfer.getSessionVersionBefore(),
                transfer.getSessionVersionAfter(), transfer.getTransferredAt());
    }
}
