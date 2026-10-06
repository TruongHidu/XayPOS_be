package com.possaas.modules.table.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.table.entity.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TableAudit {
    private final AuditService audit;

    public Map<String, Object> snapshot(TableArea a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.getName()); m.put("description", a.getDescription());
        m.put("displayOrder", a.getDisplayOrder()); m.put("active", a.isActive());
        m.put("deletedAt", a.getDeletedAt() == null ? null : a.getDeletedAt().toString());
        return m;
    }

    public Map<String, Object> snapshot(RestaurantTable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("areaId", t.getAreaId()); m.put("code", t.getCode()); m.put("name", t.getName());
        m.put("capacity", t.getCapacity()); m.put("status", t.getStatus().name());
        m.put("displayOrder", t.getDisplayOrder());
        m.put("deletedAt", t.getDeletedAt() == null ? null : t.getDeletedAt().toString());
        // QR token and paths must never enter audit, even when a token is rotated.
        return m;
    }

    public Map<String, Object> snapshot(TableSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tableId", s.getTableId()); m.put("sessionCode", s.getSessionCode());
        m.put("status", s.getStatus().name()); m.put("guestCount", s.getGuestCount()); m.put("note", s.getNote());
        m.put("openedBy", s.getOpenedBy()); m.put("openedAt", s.getOpenedAt().toString());
        m.put("closedBy", s.getClosedBy()); m.put("closedAt", s.getClosedAt() == null ? null : s.getClosedAt().toString());
        m.put("cancelReason", s.getCancelReason());
        return m;
    }

    public void record(CurrentUser actor, String action, TableRecord record, Map<String, Object> before,
            Map<String, Object> after, String ip) {
        String type = record instanceof TableArea ? "table_areas"
                : record instanceof RestaurantTable ? "restaurant_tables" : "table_sessions";
        audit.record(actor.restaurantId(), actor.userId(), action, type, record.getId(), before, after, ip);
    }
}
