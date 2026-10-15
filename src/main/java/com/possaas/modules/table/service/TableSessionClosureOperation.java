package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.application.port.TableSessionClosureReadiness;
import com.possaas.modules.table.application.port.TableGuestAccessLifecycle;
import com.possaas.modules.table.entity.TableSession;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared session mutation for manual closure and atomic DINE_IN completion. */
@Component
@RequiredArgsConstructor
public class TableSessionClosureOperation {
    private final TableSessionRepository sessions;
    private final TableSessionClosureReadiness readiness;
    private final TableAudit audit;
    private final TableGuestAccessLifecycle qrRequests;

    public void requirePermission(CurrentUser actor) {
        if (!actor.hasPermission("TABLE_CLOSE"))
            throw new AccessDeniedException("Required table permission is missing");
    }

    public void requireOpenOrClosed(TableSession session) {
        if (session.getStatus() != TableSessionStatus.OPEN && session.getStatus() != TableSessionStatus.CLOSED)
            throw new ConflictException("TABLE_SESSION_NOT_OPEN", "Only an open session can be closed");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TableSession close(CurrentUser actor, TableSession session, Instant at, String ip) {
        requirePermission(actor);
        requireOpenOrClosed(session);
        if (session.getStatus() == TableSessionStatus.CLOSED) return session;
        readiness.requireClosable(actor.restaurantId(), session.getId());
        var now = at.truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(session.getOpenedAt()))
            throw new ConflictException("TABLE_SESSION_NOT_READY_TO_CLOSE", "Session cannot close before it opened");
        var before = audit.snapshot(session);
        session.setStatus(TableSessionStatus.CLOSED);
        session.setClosedAt(now);
        session.setClosedBy(actor.userId());
        session.setUpdatedAt(now);
        sessions.saveAndFlush(session);
        qrRequests.invalidateTable(actor.restaurantId(), session.getTableId(), "SESSION_CLOSED", actor.userId(), now, ip);
        audit.record(actor, "TABLE_SESSION_CLOSED", session, before, audit.snapshot(session), ip);
        sessions.flush();
        return session;
    }
}
