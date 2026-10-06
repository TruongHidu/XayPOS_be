package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.table.entity.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TableSessionLifecyclePolicy {
    public void requireOpen(TableSession session) {
        if (session.getStatus() != TableSessionStatus.OPEN)
            throw new ConflictException("INVALID_TABLE_SESSION_TRANSITION", "Only an open session can be changed");
    }

    public void cancel(TableSession session, UUID actor, String reason, Instant now) {
        requireOpen(session);
        Instant endedAt = now.truncatedTo(ChronoUnit.MICROS);
        if (endedAt.isBefore(session.getOpenedAt()))
            throw new ConflictException("INVALID_TABLE_SESSION_TRANSITION", "Session cannot end before it opened");
        session.setStatus(TableSessionStatus.CANCELLED);
        session.setClosedBy(actor);
        session.setClosedAt(endedAt);
        session.setCancelReason(reason);
    }
}
