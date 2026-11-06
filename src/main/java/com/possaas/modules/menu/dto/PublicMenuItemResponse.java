package com.possaas.modules.menu.dto;

import com.possaas.modules.menu.entity.AvailabilityStatus;
import java.math.BigDecimal;
import java.util.UUID;

/** Public allowlist: deliberately independent of the internal MenuItemResponse. */
public record PublicMenuItemResponse(UUID id, Group group, String name, String description,
        String imageUrl, String baseUnit, BigDecimal salePrice, AvailabilityStatus availabilityStatus) {
    public record Group(UUID id, String name) {}
}
