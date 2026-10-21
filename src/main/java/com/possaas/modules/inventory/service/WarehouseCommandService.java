package com.possaas.modules.inventory.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.dto.WarehouseRequests;
import com.possaas.modules.inventory.dto.WarehouseResponse;
import com.possaas.modules.inventory.entity.Warehouse;
import com.possaas.modules.inventory.repository.WarehouseRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class WarehouseCommandService {
    private final InventoryAccessService access;
    private final WarehouseRepository warehouses;
    private final WarehouseLifecyclePolicy lifecycle;
    private final InventoryValidationPolicy validation;
    private final WarehouseResponseMapper mapper;
    private final InventoryAudit audit;
    private final Clock clock;

    public WarehouseResponse create(WarehouseRequests.Create request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Warehouse warehouse = new Warehouse();
        boolean first = warehouses.findByRestaurantIdAndDefaultWarehouseTrueAndDeletedAtIsNull(actor.restaurantId())
                .isEmpty();
        warehouse.initialize(actor.restaurantId(), validation.code(request.code()), validation.name(request.name()),
                first, clock.instant());
        warehouses.saveAndFlush(warehouse);
        audit.warehouse(actor, "WAREHOUSE_CREATED", warehouse, null, audit.warehouse(warehouse), ipAddress);
        return mapper.map(warehouse);
    }

    public WarehouseResponse update(UUID warehouseId, WarehouseRequests.Update request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Warehouse warehouse = access.warehouse(actor.restaurantId(), warehouseId);
        if (request.code() == null && request.name() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
        String code = request.code() == null ? warehouse.getCode() : validation.code(request.code());
        String name = request.name() == null ? warehouse.getName() : validation.name(request.name());
        if (Objects.equals(code, warehouse.getCode()) && Objects.equals(name, warehouse.getName()))
            return mapper.map(warehouse);
        lifecycle.requireVersion(warehouse, request.expectedVersion());
        Map<String, Object> before = audit.warehouse(warehouse);
        warehouse.setCode(code);
        warehouse.setName(name);
        persist(actor, warehouse, "WAREHOUSE_UPDATED", before, ipAddress);
        return mapper.map(warehouse);
    }

    public WarehouseResponse status(UUID warehouseId, WarehouseRequests.Status request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Warehouse warehouse = access.warehouse(actor.restaurantId(), warehouseId);
        if (warehouse.isActive() == request.active())
            return mapper.map(warehouse);
        lifecycle.requireVersion(warehouse, request.expectedVersion());
        if (!request.active())
            lifecycle.requireCanDisable(warehouse);
        Map<String, Object> before = audit.warehouse(warehouse);
        warehouse.setActive(request.active());
        persist(actor, warehouse, request.active() ? "WAREHOUSE_ENABLED" : "WAREHOUSE_DISABLED", before,
                ipAddress);
        return mapper.map(warehouse);
    }

    public WarehouseResponse makeDefault(UUID warehouseId, WarehouseRequests.MakeDefault request, String ipAddress) {
        CurrentUser actor = access.require(true);
        Warehouse target = access.warehouse(actor.restaurantId(), warehouseId);
        if (target.isDefaultWarehouse())
            return mapper.map(target);
        lifecycle.requireVersion(target, request.expectedVersion());
        lifecycle.requireCanBecomeDefault(target);
        Warehouse old = warehouses.findByRestaurantIdAndDefaultWarehouseTrueAndDeletedAtIsNull(actor.restaurantId())
                .orElse(null);
        UUID oldId = old == null ? null : old.getId();
        if (old != null) {
            old.setDefaultWarehouse(false);
            old.touch(clock.instant());
            warehouses.saveAndFlush(old);
        }
        Map<String, Object> before = audit.warehouse(target);
        target.setDefaultWarehouse(true);
        target.touch(clock.instant());
        warehouses.saveAndFlush(target);
        Map<String, Object> after = new LinkedHashMap<>(audit.warehouse(target));
        after.put("oldDefaultWarehouseId", oldId);
        after.put("newDefaultWarehouseId", target.getId());
        audit.warehouse(actor, "WAREHOUSE_DEFAULT_CHANGED", target, before, after, ipAddress);
        return mapper.map(target);
    }

    public void delete(UUID warehouseId, long expectedVersion, String ipAddress) {
        CurrentUser actor = access.require(true);
        Warehouse warehouse = access.warehouseIncludingDeleted(actor.restaurantId(), warehouseId);
        if (warehouse.getDeletedAt() != null)
            return;
        lifecycle.requireVersion(warehouse, expectedVersion);
        boolean hasOther = warehouses.existsByRestaurantIdAndActiveTrueAndDeletedAtIsNullAndIdNot(
                actor.restaurantId(), warehouseId);
        lifecycle.requireCanDelete(warehouse, hasOther);
        Map<String, Object> before = audit.warehouse(warehouse);
        warehouse.softDelete(clock.instant());
        warehouses.saveAndFlush(warehouse);
        audit.warehouse(actor, "WAREHOUSE_DELETED", warehouse, before, audit.warehouse(warehouse), ipAddress);
    }

    private void persist(CurrentUser actor, Warehouse warehouse, String action, Map<String, Object> before,
            String ipAddress) {
        warehouse.touch(clock.instant());
        warehouses.saveAndFlush(warehouse);
        audit.warehouse(actor, action, warehouse, before, audit.warehouse(warehouse), ipAddress);
    }
}
