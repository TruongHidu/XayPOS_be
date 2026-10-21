package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog;
import com.possaas.modules.inventory.dto.InventoryBalanceRequests;
import com.possaas.modules.inventory.dto.InventoryBalanceResponse;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class InventoryBalanceCommandService {
    private final InventoryAccessService access;
    private final TrackedIngredientCatalog ingredients;
    private final InventoryBalanceRepository balances;
    private final InventoryBalancePolicy policy;
    private final InventoryValidationPolicy validation;
    private final InventoryBalanceResponseMapper mapper;
    private final InventoryAudit audit;
    private final Clock clock;

    public InventoryBalanceResponse updateThreshold(UUID warehouseId, UUID ingredientId,
            InventoryBalanceRequests.UpdateThreshold request, String ipAddress) {
        CurrentUser actor = access.require(true);
        var warehouse = access.warehouse(actor.restaurantId(), warehouseId);
        if (!warehouse.isActive())
            throw new ConflictException("WAREHOUSE_INACTIVE", "Inactive warehouse cannot be configured");
        var ingredient = ingredients.requireTracked(actor.restaurantId(), ingredientId);
        BigDecimal threshold = validation.threshold(request.minQuantity());
        var existing = balances.findByRestaurantIdAndWarehouseIdAndItemId(
                actor.restaurantId(), warehouseId, ingredientId);
        if (existing.isEmpty()) {
            policy.requireLogicalVersion(request.expectedVersion());
            if (threshold.signum() == 0)
                return mapper.logical(warehouse, ingredient);
            InventoryBalance balance = new InventoryBalance();
            balance.initialize(actor.restaurantId(), warehouseId, ingredientId, threshold, clock.instant());
            balances.saveAndFlush(balance);
            audit.threshold(actor, balance, null, ipAddress);
            return mapper.map(balance, warehouse, ingredient);
        }
        InventoryBalance balance = existing.orElseThrow();
        policy.requireExistingVersion(balance, request.expectedVersion());
        if (balance.getMinQuantity().compareTo(threshold) == 0)
            return mapper.map(balance, warehouse, ingredient);
        Map<String, Object> before = audit.threshold(balance);
        balance.setMinQuantity(threshold);
        balance.touch(clock.instant());
        balances.saveAndFlush(balance);
        audit.threshold(actor, balance, before, ipAddress);
        return mapper.map(balance, warehouse, ingredient);
    }
}
