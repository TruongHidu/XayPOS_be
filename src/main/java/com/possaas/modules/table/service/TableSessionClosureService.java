package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.table.application.port.TableSessionClosureReadiness;
import com.possaas.modules.table.dto.TableSessionResponse;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class TableSessionClosureService {
    private final TableAccessService access;
    private final TableSessionRepository sessions;
    private final TableLifecyclePolicy lifecycle;
    private final TableSessionClosureReadiness readiness;
    private final TableResponseMapper mapper;
    private final TableAudit audit;
    private final Clock clock;

    @Transactional
    public TableSessionResponse close(UUID id,long expectedVersion,String ip) {
        var actor=access.require(true);
        if(!actor.hasPermission("TABLE_CLOSE")) throw new AccessDeniedException("Required table permission is missing");
        var session=access.session(actor.restaurantId(),id);
        if(session.getStatus()==TableSessionStatus.CLOSED) return mapper.session(session);
        if(session.getStatus()!=TableSessionStatus.OPEN)
            throw new ConflictException("TABLE_SESSION_NOT_OPEN","Only an open session can be closed");
        lifecycle.requireVersion(session,expectedVersion);
        readiness.requireClosable(actor.restaurantId(),id);
        var before=audit.snapshot(session);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(now.isBefore(session.getOpenedAt()))
            throw new ConflictException("TABLE_SESSION_NOT_READY_TO_CLOSE","Session cannot close before it opened");
        session.setStatus(TableSessionStatus.CLOSED); session.setClosedAt(now); session.setClosedBy(actor.userId());
        session.setUpdatedAt(now); sessions.saveAndFlush(session);
        audit.record(actor,"TABLE_SESSION_CLOSED",session,before,audit.snapshot(session),ip);
        sessions.flush();
        return mapper.session(session);
    }
}
