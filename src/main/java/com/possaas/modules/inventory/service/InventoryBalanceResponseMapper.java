package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog.TrackedIngredientSnapshot;
import com.possaas.modules.inventory.dto.InventoryBalanceResponse;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.entity.Warehouse;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class InventoryBalanceResponseMapper {
    public InventoryBalanceResponse map(InventoryBalance balance, Warehouse warehouse,
            TrackedIngredientSnapshot ingredient) {
        BigDecimal quantity = balance.getQuantity().setScale(3);
        BigDecimal minimum = balance.getMinQuantity().setScale(3);
        return new InventoryBalanceResponse(
                balance.getId(), true, warehouse.getId(), warehouse.getCode(), ingredient.id(), ingredient.sku(),
                ingredient.name(), ingredient.baseUnit(), ingredient.active(), quantity,
                balance.getReservedQuantity().setScale(3), quantity.subtract(balance.getReservedQuantity()).setScale(3), minimum,
                minimum.signum() > 0 && quantity.compareTo(minimum) <= 0,
                balance.getVersion(), balance.getCreatedAt(), balance.getUpdatedAt());
    }

    public InventoryBalanceResponse logical(Warehouse warehouse, TrackedIngredientSnapshot ingredient) {
        BigDecimal zero = BigDecimal.ZERO.setScale(3);
        return new InventoryBalanceResponse(
                null, false, warehouse.getId(), warehouse.getCode(), ingredient.id(), ingredient.sku(),
                ingredient.name(), ingredient.baseUnit(), ingredient.active(), zero, zero, zero, zero, false,
                null, null, null);
    }
}
