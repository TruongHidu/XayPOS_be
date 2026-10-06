package com.possaas.modules.table.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantTableCommandService {
    private final TableAccessService access;
    private final RestaurantTableRepository tables;
    private final TableValidationPolicy validation;
    private final TableLifecyclePolicy lifecycle;
    private final TableResponseMapper mapper;
    private final TableQrTokenGenerator tokens;
    private final TableAudit audit;
    private final Clock clock;

    public RestaurantTableResponse create(TableRequests.CreateTable r, String ip) {
        var actor = access.require(true);
        access.assignableArea(actor.restaurantId(), r.areaId());
        var table = new RestaurantTable();
        table.initialize(actor.restaurantId(), clock.instant());
        table.setAreaId(r.areaId());
        table.setCode(validation.code(r.code()));
        table.setName(validation.required(r.name(), 100));
        table.setCapacity(validation.positiveShort(r.capacity(), 4));
        table.setDisplayOrder(validation.displayOrder(r.displayOrder()));
        table.setStatus(r.status() == null ? TableStatus.AVAILABLE : r.status());
        table.setQrToken(tokens.generate());
        tables.saveAndFlush(table);
        audit.record(actor, "TABLE_CREATED", table, null, audit.snapshot(table), ip);
        return mapper.table(table);
    }

    public RestaurantTableResponse update(UUID id, TableRequests.UpdateTable r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), id, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        validation.requireChanges(r.code() != null || r.name() != null || r.capacity() != null || r.displayOrder() != null);
        String code = r.code() == null ? table.getCode() : validation.code(r.code());
        short capacity = r.capacity() == null ? table.getCapacity() : validation.positiveShort(r.capacity(), 4);
        if (!code.equals(table.getCode()) || capacity != table.getCapacity())
            lifecycle.requireUnoccupied(access.occupied(actor.restaurantId(), id));
        var before = audit.snapshot(table);
        table.setCode(code);
        table.setCapacity(capacity);
        if (r.name() != null) table.setName(validation.required(r.name(), 100));
        if (r.displayOrder() != null) table.setDisplayOrder(validation.displayOrder(r.displayOrder()));
        if (!before.equals(audit.snapshot(table))) persist(actor, table, "TABLE_UPDATED", before, ip);
        return mapper.table(table);
    }

    public RestaurantTableResponse status(UUID id, TableRequests.Status r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), id, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        if (table.getStatus() == r.status()) return mapper.table(table);
        if (r.status() == TableStatus.INACTIVE) lifecycle.requireUnoccupied(access.occupied(actor.restaurantId(), id));
        var before = audit.snapshot(table);
        table.setStatus(r.status());
        persist(actor, table, r.status() == TableStatus.AVAILABLE ? "TABLE_ENABLED" : "TABLE_DISABLED", before, ip);
        return mapper.table(table);
    }

    public RestaurantTableResponse area(UUID id, TableRequests.AreaAssignment r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), id, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        if (Objects.equals(table.getAreaId(), r.areaId())) return mapper.table(table);
        lifecycle.requireUnoccupied(access.occupied(actor.restaurantId(), id));
        access.assignableArea(actor.restaurantId(), r.areaId());
        var before = audit.snapshot(table);
        table.setAreaId(r.areaId());
        persist(actor, table, "TABLE_AREA_CHANGED", before, ip);
        return mapper.table(table);
    }

    public void delete(UUID id, long expectedVersion, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), id, true);
        if (table.getDeletedAt() != null) return;
        lifecycle.requireVersion(table, expectedVersion);
        lifecycle.requireUnoccupied(access.occupied(actor.restaurantId(), id));
        var before = audit.snapshot(table);
        table.setUpdatedAt(clock.instant());
        table.setDeletedAt(table.getUpdatedAt());
        tables.saveAndFlush(table);
        audit.record(actor, "TABLE_DELETED", table, before, audit.snapshot(table), ip);
    }

    public TableQrResponse rotateQr(UUID id, TableRequests.Version r, String ip) {
        var actor = access.require(true);
        var table = access.table(actor.restaurantId(), id, false);
        lifecycle.requireVersion(table, r.expectedVersion());
        var before = audit.snapshot(table);
        table.setQrToken(tokens.rotate(table.getQrToken()));
        persist(actor, table, "TABLE_QR_ROTATED", before, ip);
        return TableQrResponse.from(table);
    }

    private void persist(CurrentUser actor, RestaurantTable table, String action, Map<String, Object> before, String ip) {
        table.setUpdatedAt(clock.instant());
        tables.saveAndFlush(table);
        audit.record(actor, action, table, before, audit.snapshot(table), ip);
    }
}
