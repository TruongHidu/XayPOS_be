package com.possaas.modules.table.service;

import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import com.possaas.modules.table.application.port.TableGuestAccessLifecycle;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TableSessionTransferService {
    private final TableAccessService access;
    private final TableSessionTransferPolicy policy;
    private final TableSessionTransferIdempotency idempotency;
    private final TableSessionRepository sessions;
    private final TableSessionTransferRepository transfers;
    private final TableResponseMapper mapper;
    private final TableAudit audit;
    private final Clock clock;
    private final TableGuestAccessLifecycle qrRequests;

    @Transactional
    public Result transfer(UUID sessionId, TableRequests.TransferSession input, String rawKey, String ip) {
        var actor = access.require(true); // Same restaurant lock as seating and ORDER commands, held until commit.
        policy.requirePermission(actor);
        var request = policy.normalize(input);
        var key = idempotency.key(rawKey);
        var hash = idempotency.hash(actor.userId(), sessionId, request);
        if (idempotency.replay(actor.restaurantId(), sessionId, actor.userId(), key, hash))
            return new Result(mapper.session(access.session(actor.restaurantId(), sessionId)), true);

        var session = access.session(actor.restaurantId(), sessionId);
        policy.requireSession(session, request.expectedVersion());
        var source = access.table(actor.restaurantId(), session.getTableId(), true);
        policy.requireSource(session, source, sessions.findAllByRestaurantIdAndTableIdAndStatus(
                actor.restaurantId(), source.getId(), TableSessionStatus.OPEN));
        var target = access.table(actor.restaurantId(), request.targetTableId(), false);
        var area = target.getAreaId() == null ? null : access.area(actor.restaurantId(), target.getAreaId(), true);
        policy.requireTarget(source, target, area, access.occupied(actor.restaurantId(), target.getId()));

        long beforeVersion = session.getVersion();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        session.setTableId(target.getId());
        session.setUpdatedAt(now);
        sessions.saveAndFlush(session);
        transfers.saveAndFlush(new TableSessionTransfer(session, source, target, actor.userId(), request.reason(),
                beforeVersion, key, hash, now));
        qrRequests.invalidateTable(actor.restaurantId(), source.getId(), "SESSION_TRANSFERRED", actor.userId(), now, ip);
        qrRequests.invalidateTable(actor.restaurantId(), target.getId(), "SESSION_TRANSFERRED", actor.userId(), now, ip);
        Map<String, Object> before = Map.of("tableId", source.getId(), "version", beforeVersion);
        var after = new LinkedHashMap<String, Object>();
        after.put("tableId", target.getId()); after.put("version", session.getVersion());
        after.put("transferredAt", now.toString()); after.put("reason", request.reason());
        audit.record(actor, "TABLE_SESSION_TRANSFERRED", session, before, after, ip);
        return new Result(mapper.session(session), false);
    }

    public record Result(TableSessionResponse response, boolean replay) {}
}
