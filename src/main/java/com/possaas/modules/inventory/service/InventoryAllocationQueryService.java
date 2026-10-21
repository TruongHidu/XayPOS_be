package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.application.port.OrderAllocationItemQuery;
import com.possaas.modules.inventory.dto.InventoryAllocationResponse;
import com.possaas.modules.inventory.repository.InventoryAllocationQueryRepository;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryAllocationQueryService {
    private final InventoryAccessService access;
    private final FeatureAccessChecker features;
    private final OrderAllocationItemQuery orderItems;
    private final InventoryAllocationQueryRepository queries;
    private final OrderInventoryAllocationCommand allocations;

    public InventoryAllocationResponse find(UUID orderId, UUID itemId) {
        var actor = access.require(false);
        if (!actor.hasPermission("ORDER_VIEW") || !actor.hasPermission("RECIPE_VIEW")
                || !actor.hasPermission("INVENTORY_VIEW"))
            throw new AccessDeniedException("Order, recipe and inventory view permissions are required");
        features.requireFeature(actor.restaurantId(), "ORDER_MANAGEMENT");
        features.requireFeature(actor.restaurantId(), "RECIPE_MANAGEMENT");
        var line = orderItems.find(actor.restaurantId(), orderId, itemId);
        allocations.validate(actor.restaurantId(), orderId, List.of(new OrderInventoryAllocationCommand.LineState(
                line.orderItemId(), line.status(), line.submitted(), line.quantity(), line.unit())));
        var result = queries.find(actor.restaurantId(), orderId, itemId);
        if (result.isPresent()) return result.get();
        if (line.submitted())
            throw new ConflictException("INVENTORY_ALLOCATION_NOT_FOUND", "Submitted item inventory decision is missing");
        return new InventoryAllocationResponse(null, orderId, itemId, null, "NOT_SUBMITTED", null,
                null, null, null, null, List.of());
    }
}
