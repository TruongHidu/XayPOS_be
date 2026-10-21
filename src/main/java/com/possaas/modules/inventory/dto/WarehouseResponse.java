package com.possaas.modules.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(
        UUID id,
        String code,
        String name,
        boolean isDefault,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
