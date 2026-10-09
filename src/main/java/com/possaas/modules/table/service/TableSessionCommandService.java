package com.possaas.modules.table.service;

import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.application.port.TableSessionUsagePolicy;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TableSessionCommandService {
    private final TableAccessService access;
    private final TableSessionRepository sessions;
    private final TableValidationPolicy validation;
    private final TableLifecyclePolicy lifecycle;
    private final TableSessionLifecyclePolicy sessionLifecycle;
    private final TableResponseMapper mapper;
    private final TableAudit audit;
    private final Clock clock;
    private final TableSessionUsagePolicy usage;
    private final TableSessionOpeningOperation opening;

    public TableSessionResponse open(UUID tableId, TableRequests.OpenSession r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), tableId, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        var session = opening.open(actor.restaurantId(),tableId,r.guestCount(),r.note(),clock.instant(),ip);
        return mapper.session(session);
    }

    public TableSessionResponse update(UUID id, TableRequests.UpdateSession r, String ip) {
        var actor = access.require(true);
        var session = access.session(actor.restaurantId(), id);
        sessionLifecycle.requireOpen(session);
        lifecycle.requireVersion(session, r.expectedVersion());
        validation.requireChanges(r.guestCount() != null || r.note() != null);
        var before = audit.snapshot(session);
        if (r.guestCount() != null) session.setGuestCount(validation.positiveShort(r.guestCount(), 1));
        if (r.note() != null) session.setNote(validation.optional(r.note(), 2000));
        if (!before.equals(audit.snapshot(session))) {
            session.setUpdatedAt(clock.instant());
            sessions.saveAndFlush(session);
            audit.record(actor, "TABLE_SESSION_UPDATED", session, before, audit.snapshot(session), ip);
        }
        return mapper.session(session);
    }

    /**
     * Cancels seating only when the mandatory downstream usage policy permits it.
     * Order creation and termination share the restaurant transaction/lock boundary.
     */
    public TableSessionResponse cancel(UUID id, TableRequests.CancelSession r, String ip) {
        var actor = access.require(true);
        var session = access.session(actor.restaurantId(), id);
        if (session.getStatus() == TableSessionStatus.CANCELLED) return mapper.session(session);
        sessionLifecycle.requireOpen(session);
        lifecycle.requireVersion(session, r.expectedVersion());
        usage.requireCancellable(actor.restaurantId(), id);
        var before = audit.snapshot(session);
        var now = clock.instant();
        sessionLifecycle.cancel(session, actor.userId(), validation.required(r.reason(), 1000), now);
        session.setUpdatedAt(now);
        sessions.saveAndFlush(session);
        audit.record(actor, "TABLE_SESSION_CANCELLED", session, before, audit.snapshot(session), ip);
        return mapper.session(session);
    }
}
