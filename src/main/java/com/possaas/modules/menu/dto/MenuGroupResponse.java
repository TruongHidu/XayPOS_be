package com.possaas.modules.menu.dto;

import com.possaas.modules.menu.entity.ItemGroup;
import java.time.Instant;
import java.util.UUID;

public record MenuGroupResponse(UUID id, String name, int displayOrder, boolean active, long version, Instant createdAt,
        Instant updatedAt) {
    public static MenuGroupResponse from(ItemGroup g) {
        return new MenuGroupResponse(g.getId(), g.getName(), g.getDisplayOrder(), g.isActive(), g.getVersion(),
                g.getCreatedAt(), g.getUpdatedAt());
    }
}
