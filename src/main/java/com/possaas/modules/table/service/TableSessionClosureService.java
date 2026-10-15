package com.possaas.modules.table.service;

import com.possaas.modules.table.dto.TableSessionResponse;
import com.possaas.modules.table.entity.TableSessionStatus;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class TableSessionClosureService {
    private final TableAccessService access;
    private final TableLifecyclePolicy lifecycle;
    private final TableSessionClosureOperation closing;
    private final TableResponseMapper mapper;
    private final Clock clock;

    @Transactional
    public TableSessionResponse close(UUID id,long expectedVersion,String ip) {
        var actor=access.require(true);
        closing.requirePermission(actor);
        var session=access.session(actor.restaurantId(),id);
        closing.requireOpenOrClosed(session);
        if(session.getStatus()==TableSessionStatus.CLOSED) return mapper.session(session);
        lifecycle.requireVersion(session,expectedVersion);
        return mapper.session(closing.close(actor,session,clock.instant(),ip));
    }
}
