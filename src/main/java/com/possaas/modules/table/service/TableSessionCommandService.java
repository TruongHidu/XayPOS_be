package com.possaas.modules.table.service;

import com.possaas.modules.table.dto.*;
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

    public TableSessionResponse open(UUID tableId, TableRequests.OpenSession r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), tableId, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        var area = table.getAreaId() == null ? null : access.area(actor.restaurantId(), table.getAreaId(), true);
        lifecycle.requireOpenable(table, area, access.occupied(actor.restaurantId(), tableId));
        var session = new TableSession();
        session.initialize(actor.restaurantId(), clock.instant());
        session.setTableId(tableId);
        session.setSessionCode("TS-" + UUID.randomUUID().toString().replace("-", ""));
        session.setGuestCount(validation.positiveShort(r.guestCount(), 1));
        session.setNote(validation.optional(r.note(), 2000));
        session.setOpenedBy(actor.userId());
        session.setOpenedAt(session.getCreatedAt());
        sessions.saveAndFlush(session);
        audit.record(actor, "TABLE_SESSION_OPENED", session, null, audit.snapshot(session), ip);
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
     * Phase 1: cancels a seating session, not an order or a settled bill.
     * Before introducing ANY order write API, add a real session-usage policy here.
     * Order creation and termination must share this tenant transaction/lock boundary;
     * missing usage integration must fail closed. Do not install an always-empty adapter.
     */
    public TableSessionResponse cancel(UUID id, TableRequests.CancelSession r, String ip) {
        var actor = access.require(true);
        var session = access.session(actor.restaurantId(), id);
        if (session.getStatus() == TableSessionStatus.CANCELLED) return mapper.session(session);
        sessionLifecycle.requireOpen(session);
        lifecycle.requireVersion(session, r.expectedVersion());
        var before = audit.snapshot(session);
        var now = clock.instant();
        sessionLifecycle.cancel(session, actor.userId(), validation.required(r.reason(), 1000), now);
        session.setUpdatedAt(now);
        sessions.saveAndFlush(session);
        audit.record(actor, "TABLE_SESSION_CANCELLED", session, before, audit.snapshot(session), ip);
        return mapper.session(session);
    }
}
