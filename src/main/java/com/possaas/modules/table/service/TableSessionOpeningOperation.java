package com.possaas.modules.table.service;

import com.possaas.modules.table.entity.TableSession;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared manual/ORDER writer. No independent commit, Clock read, HTTP DTO or fabricated version. */
@Component @RequiredArgsConstructor
public class TableSessionOpeningOperation {
    private final TableAccessService access;
    private final TableSessionOpeningPolicy authorization;
    private final TableLifecyclePolicy lifecycle;
    private final TableValidationPolicy validation;
    private final TableSessionRepository sessions;
    private final TableAudit audit;

    @Transactional(propagation=Propagation.MANDATORY)
    public TableSession open(UUID tenant,UUID tableId,Integer guests,String note,Instant now,String ip) {
        var actor=access.require(true); // Shared restaurant lock, feature and current actor.
        authorization.requireAllowed(actor,tenant);
        var table=access.table(tenant,tableId,false);
        var area=table.getAreaId()==null?null:access.area(tenant,table.getAreaId(),true);
        lifecycle.requireOpenable(table,area,access.occupied(tenant,tableId));
        var session=new TableSession();
        session.initialize(tenant,Objects.requireNonNull(now));
        session.setTableId(tableId);
        session.setSessionCode("TS-"+UUID.randomUUID().toString().replace("-",""));
        session.setGuestCount(validation.positiveShort(guests,1));
        session.setNote(validation.optional(note,2000));
        session.setOpenedBy(actor.userId());
        session.setOpenedAt(session.getCreatedAt());
        sessions.saveAndFlush(session);
        audit.record(actor,"TABLE_SESSION_OPENED",session,null,audit.snapshot(session),ip);
        return session;
    }
}
