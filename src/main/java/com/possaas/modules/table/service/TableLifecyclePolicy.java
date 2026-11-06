package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.table.entity.*;
import org.springframework.stereotype.Component;

@Component
public class TableLifecyclePolicy {
    public void requireVersion(TableRecord record, Long expected) {
        if (expected == null || expected < 0) throw TableValidationPolicy.invalid("expectedVersion is required and must not be negative");
        if (record.getVersion() != expected)
            throw new ConflictException("CONCURRENT_TABLE_UPDATE", "Resource changed; reload before retrying");
    }

    public void requireUnoccupied(boolean occupied) {
        if (occupied) throw new ConflictException("TABLE_HAS_OPEN_SESSION", "The table has an open session");
    }

    public boolean canOpen(RestaurantTable table, TableArea area, boolean occupied) {
        return table.getDeletedAt() == null && table.getStatus() == TableStatus.AVAILABLE && !occupied
                && (table.getAreaId() == null || area != null && area.isActive() && area.getDeletedAt() == null);
    }

    public void requireOpenable(RestaurantTable table, TableArea area, boolean occupied) {
        if (table.getDeletedAt() != null || table.getStatus() != TableStatus.AVAILABLE)
            throw new ConflictException("TABLE_INACTIVE", "Table is inactive");
        if (table.getAreaId() != null && (area == null || !area.isActive() || area.getDeletedAt() != null))
            throw new ConflictException("TABLE_AREA_INACTIVE", "Table area is inactive");
        if (occupied) throw new ConflictException("TABLE_ALREADY_OCCUPIED", "Table already has an open session");
    }
}
