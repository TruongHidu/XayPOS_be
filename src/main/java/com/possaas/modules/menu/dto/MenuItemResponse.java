package com.possaas.modules.menu.dto;

import com.possaas.modules.menu.entity.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;

public record MenuItemResponse(UUID id, Group group, String sku, String name, String baseUnit, String description,
        String imageUrl, BigDecimal salePrice,BigDecimal costPrice, boolean active, AvailabilityStatus availabilityStatus, boolean sellable,
        long version, Instant createdAt, Instant updatedAt) {
    public record Group(UUID id, String name, boolean active) {
    }

    public static MenuItemResponse from(Item i, ItemGroup g, boolean sellable) {
        return new MenuItemResponse(i.getId(), g == null ? null : new Group(g.getId(), g.getName(), g.isActive()),
                i.getSku(), i.getName(), i.getBaseUnit(), i.getDescription(), i.getImageUrl(), i.getSalePrice(), i.getCostPrice(),
                i.isActive(), i.getAvailabilityStatus(), sellable, i.getVersion(), i.getCreatedAt(), i.getUpdatedAt());
    }
}
