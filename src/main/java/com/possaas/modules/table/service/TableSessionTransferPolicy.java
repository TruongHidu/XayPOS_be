package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.dto.TableRequests;
import com.possaas.modules.table.entity.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TableSessionTransferPolicy {
    private final TableValidationPolicy validation;
    private final TableLifecyclePolicy lifecycle;
    private final TableSessionLifecyclePolicy sessionLifecycle;

    public void requirePermission(CurrentUser actor) {
        if (!actor.hasPermission("TABLE_TRANSFER"))
            throw new AccessDeniedException("Transferring a session requires TABLE_TRANSFER");
    }

    public TableRequests.TransferSession normalize(TableRequests.TransferSession request) {
        if (request == null || request.targetTableId() == null || request.expectedVersion() == null
                || request.expectedVersion() < 0)
            throw TableValidationPolicy.invalid("Target table and nonnegative session version are required");
        return new TableRequests.TransferSession(request.targetTableId(), request.expectedVersion(),
                validation.optional(request.reason(), 1000));
    }

    public void requireSession(TableSession session, long expectedVersion) {
        sessionLifecycle.requireOpen(session);
        lifecycle.requireVersion(session, expectedVersion);
    }

    public void requireSource(TableSession session, RestaurantTable source, List<TableSession> openSessions) {
        if (!session.getTableId().equals(source.getId())
                || !session.getRestaurantId().equals(source.getRestaurantId()) || source.getDeletedAt() != null
                || openSessions.size() != 1 || !session.getId().equals(openSessions.getFirst().getId()))
            throw new ConflictException("INCONSISTENT_TABLE_SESSION_STATE", "Source has inconsistent open session data");
    }

    public void requireTarget(RestaurantTable source, RestaurantTable target, TableArea area, boolean occupied) {
        if (source.getId().equals(target.getId()))
            throw new ConflictException("SAME_TABLE_TRANSFER_NOT_ALLOWED", "Choose a different table");
        lifecycle.requireOpenable(target, area, occupied);
    }
}
