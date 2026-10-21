package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.WarehouseResponse;
import com.possaas.modules.inventory.entity.Warehouse;
import org.springframework.stereotype.Component;

@Component
public class WarehouseResponseMapper {
    public WarehouseResponse map(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getCode(),
                warehouse.getName(),
                warehouse.isDefaultWarehouse(),
                warehouse.isActive(),
                warehouse.getVersion(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt());
    }
}
