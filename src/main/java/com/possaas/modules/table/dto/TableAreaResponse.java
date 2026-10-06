package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.TableArea;
import java.time.Instant;
import java.util.UUID;

public record TableAreaResponse(UUID id, String name, String description, int displayOrder, boolean active,
        long version, Instant createdAt, Instant updatedAt) {
    public static TableAreaResponse from(TableArea area) {
        return new TableAreaResponse(area.getId(), area.getName(), area.getDescription(), area.getDisplayOrder(),
                area.isActive(), area.getVersion(), area.getCreatedAt(), area.getUpdatedAt());
    }
}
