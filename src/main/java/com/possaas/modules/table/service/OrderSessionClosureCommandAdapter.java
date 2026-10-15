package com.possaas.modules.table.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.application.port.OrderSessionClosureCommand;
import com.possaas.modules.table.entity.TableSessionStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderSessionClosureCommandAdapter implements OrderSessionClosureCommand {
    private final TableAccessService access;
    private final TableSessionClosureOperation closing;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isClosed(UUID tenant, UUID sessionId) {
        requireActor(tenant);
        var session = access.session(tenant, sessionId);
        closing.requireOpenOrClosed(session);
        return session.getStatus() == TableSessionStatus.CLOSED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(UUID tenant, UUID sessionId, Instant now, String ip) {
        var actor = requireActor(tenant);
        closing.close(actor, access.session(tenant, sessionId), now, ip);
    }

    private CurrentUser requireActor(UUID tenant) {
        // The ORDER caller already holds this same restaurant lock; keep the established lock order.
        var actor = access.require(true);
        if (!Objects.equals(actor.restaurantId(), tenant))
            throw new AccessDeniedException("A matching tenant context is required");
        closing.requirePermission(actor);
        return actor;
    }
}
