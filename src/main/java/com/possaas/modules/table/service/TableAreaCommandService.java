package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.TableArea;
import com.possaas.modules.table.repository.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TableAreaCommandService {
    private final TableAccessService access;
    private final TableAreaRepository areas;
    private final RestaurantTableRepository tables;
    private final TableSessionRepository sessions;
    private final TableValidationPolicy validation;
    private final TableLifecyclePolicy lifecycle;
    private final TableAudit audit;
    private final Clock clock;

    public TableAreaResponse create(TableRequests.CreateArea r, String ip) {
        var actor = access.require(true);
        var area = new TableArea();
        area.initialize(actor.restaurantId(), clock.instant());
        area.setName(validation.required(r.name(), 100));
        area.setDescription(validation.optional(r.description(), 2000));
        area.setDisplayOrder(validation.displayOrder(r.displayOrder()));
        area.setActive(r.active() == null || r.active());
        areas.saveAndFlush(area);
        audit.record(actor, "TABLE_AREA_CREATED", area, null, audit.snapshot(area), ip);
        return TableAreaResponse.from(area);
    }

    public TableAreaResponse update(UUID id, TableRequests.UpdateArea r, String ip) {
        var actor = access.require(true);
        var area = access.area(actor.restaurantId(), id, false);
        lifecycle.requireVersion(area, r.expectedVersion());
        validation.requireChanges(r.name() != null || r.description() != null || r.displayOrder() != null);
        var before = audit.snapshot(area);
        if (r.name() != null) area.setName(validation.required(r.name(), 100));
        if (r.description() != null) area.setDescription(validation.optional(r.description(), 2000));
        if (r.displayOrder() != null) area.setDisplayOrder(validation.displayOrder(r.displayOrder()));
        if (!before.equals(audit.snapshot(area))) persist(actor, area, "TABLE_AREA_UPDATED", before, ip);
        return TableAreaResponse.from(area);
    }

    public TableAreaResponse status(UUID id, TableRequests.AreaStatus r, String ip) {
        var actor = access.require(true);
        var area = access.area(actor.restaurantId(), id, false);
        lifecycle.requireVersion(area, r.expectedVersion());
        if (area.isActive() == r.active()) return TableAreaResponse.from(area);
        if (!r.active() && sessions.hasOpenSessionsInArea(actor.restaurantId(), id))
            throw new ConflictException("TABLE_AREA_HAS_OPEN_SESSIONS", "Area contains open sessions");
        var before = audit.snapshot(area);
        area.setActive(r.active());
        persist(actor, area, r.active() ? "TABLE_AREA_ENABLED" : "TABLE_AREA_DISABLED", before, ip);
        return TableAreaResponse.from(area);
    }

    public void delete(UUID id, long expectedVersion, String ip) {
        var actor = access.require(true);
        var area = access.area(actor.restaurantId(), id, true);
        if (area.getDeletedAt() != null) return;
        lifecycle.requireVersion(area, expectedVersion);
        if (tables.existsByRestaurantIdAndAreaIdAndDeletedAtIsNull(actor.restaurantId(), id))
            throw new ConflictException("TABLE_AREA_NOT_EMPTY", "Move or delete the area's tables first");
        var before = audit.snapshot(area);
        area.setUpdatedAt(clock.instant());
        area.setDeletedAt(area.getUpdatedAt());
        areas.saveAndFlush(area);
        audit.record(actor, "TABLE_AREA_DELETED", area, before, audit.snapshot(area), ip);
    }

    private void persist(CurrentUser actor, TableArea area, String action, Map<String, Object> before, String ip) {
        area.setUpdatedAt(clock.instant());
        areas.saveAndFlush(area);
        audit.record(actor, action, area, before, audit.snapshot(area), ip);
    }
}
