package com.possaas.modules.table.service;

import com.possaas.common.exception.*;
import com.possaas.common.security.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TableAccessService {
    private final CurrentTenantProvider tenants;
    private final CurrentUserProvider users;
    private final FeatureAccessChecker features;
    private final RestaurantRepository restaurants;
    private final TableAreaRepository areas;
    private final RestaurantTableRepository tables;
    private final TableSessionRepository sessions;

    public CurrentUser require(boolean mutation) {
        UUID tenant = tenants.getRequiredRestaurantId();
        if (mutation) restaurants.findByIdForUpdate(tenant)
                .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(tenant, "TABLE_MANAGEMENT");
        return users.getRequired();
    }

    public TableArea area(UUID tenant, UUID id, boolean includeDeleted) {
        return areas.findByIdAndRestaurantId(id, tenant).filter(a -> includeDeleted || a.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_AREA_NOT_FOUND", "Area not found"));
    }

    public TableArea assignableArea(UUID tenant, UUID id) {
        if (id == null) return null;
        TableArea area = area(tenant, id, false);
        if (!area.isActive()) throw new ConflictException("TABLE_AREA_INACTIVE", "Area is inactive");
        return area;
    }

    public RestaurantTable table(UUID tenant, UUID id, boolean includeDeleted) {
        return tables.findByIdAndRestaurantId(id, tenant).filter(t -> includeDeleted || t.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_NOT_FOUND", "Table not found"));
    }

    public TableSession session(UUID tenant, UUID id) {
        return sessions.findByIdAndRestaurantId(id, tenant)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_SESSION_NOT_FOUND", "Session not found"));
    }

    public boolean occupied(UUID tenant, UUID tableId) {
        return sessions.existsByRestaurantIdAndTableIdAndStatus(tenant, tableId, TableSessionStatus.OPEN);
    }
}
