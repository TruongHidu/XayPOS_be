package com.possaas.modules.table.service;

import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TableResponseMapper {
    private final TableAreaRepository areas;
    private final RestaurantTableRepository tables;
    private final TableSessionRepository sessions;
    private final TableLifecyclePolicy lifecycle;

    public List<RestaurantTableResponse> tables(UUID tenant, List<RestaurantTable> page) {
        if (page.isEmpty()) return List.of();
        var areaIds = page.stream().map(RestaurantTable::getAreaId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, TableArea> areaMap = areaIds.isEmpty() ? Map.of()
                : areas.findAllByRestaurantIdAndIdIn(tenant, areaIds).stream().collect(Collectors.toMap(TableArea::getId, Function.identity()));
        var ids = page.stream().map(RestaurantTable::getId).toList();
        var openMap = sessions.findAllByRestaurantIdAndTableIdInAndStatus(tenant, ids, TableSessionStatus.OPEN).stream()
                .collect(Collectors.toMap(TableSession::getTableId, Function.identity()));
        return page.stream().map(t -> {
            TableArea a = t.getAreaId() == null ? null : areaMap.get(t.getAreaId());
            TableSession s = openMap.get(t.getId());
            return new RestaurantTableResponse(t.getId(), t.getCode(), t.getName(), t.getCapacity(), t.getDisplayOrder(),
                    t.getStatus(), a == null ? null : new RestaurantTableResponse.Area(a.getId(), a.getName(), a.isActive() && a.getDeletedAt() == null),
                    s != null, s == null ? null : new RestaurantTableResponse.Session(s.getId(), s.getSessionCode(), s.getGuestCount(), s.getOpenedAt(), s.getVersion()),
                    lifecycle.canOpen(t, a, s != null), t.getVersion(), t.getCreatedAt(), t.getUpdatedAt());
        }).toList();
    }

    public RestaurantTableResponse table(RestaurantTable table) {
        return tables(table.getRestaurantId(), List.of(table)).getFirst();
    }

    public List<TableSessionResponse> sessions(UUID tenant, List<TableSession> page) {
        if (page.isEmpty()) return List.of();
        var ids = page.stream().map(TableSession::getTableId).collect(Collectors.toSet());
        // Deliberately include soft-deleted tables so seating history remains readable.
        var tableMap = tables.findAllByRestaurantIdAndIdIn(tenant, ids).stream()
                .collect(Collectors.toMap(RestaurantTable::getId, Function.identity()));
        return page.stream().map(s -> {
            RestaurantTable t = tableMap.get(s.getTableId());
            return new TableSessionResponse(s.getId(), s.getSessionCode(), s.getTableId(),
                    t == null ? null : t.getCode(), t == null ? null : t.getName(), s.getStatus(), s.getGuestCount(),
                    s.getNote(), s.getOpenedBy(), s.getOpenedAt(), s.getClosedBy(), s.getClosedAt(), s.getCancelReason(),
                    s.getVersion(), s.getCreatedAt(), s.getUpdatedAt());
        }).toList();
    }

    public TableSessionResponse session(TableSession session) {
        return sessions(session.getRestaurantId(), List.of(session)).getFirst();
    }
}
