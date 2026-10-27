package com.possaas.modules.order.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.order.dto.OrderRecipeSnapshotResponse;
import com.possaas.modules.order.entity.RecipeSnapshotStatus;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.repository.OrderItemRecipeSnapshotRepository;
import com.possaas.modules.order.repository.OrderItemRecipeUsageRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class OrderRecipeSnapshotQueryService {
    private final OrderAccessService access;
    private final FeatureAccessChecker features;
    private final OrderItemRepository lines;
    private final OrderItemRecipeSnapshotRepository snapshots;
    private final OrderItemRecipeUsageRepository usages;
    private final OrderRecipeSnapshotPolicy policy;

    public OrderRecipeSnapshotResponse find(UUID orderId, UUID lineId) {
        var actor = access.read();
        if (!actor.hasPermission("ORDER_VIEW") || !actor.hasPermission("RECIPE_VIEW"))
            throw new AccessDeniedException("Order and recipe view permissions are required");
        features.requireFeature(actor.restaurantId(), "RECIPE_MANAGEMENT");
        access.order(actor.restaurantId(), orderId);
        var line = lines.findByIdAndRestaurantIdAndOrderId(lineId, actor.restaurantId(), orderId)
                .orElseThrow(() -> new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND", "Order line not found"));
        var snapshot = snapshots.findByRestaurantIdAndOrderItemId(actor.restaurantId(), lineId).orElse(null);
        if (snapshot == null) {
            if (line.getSentToKitchenAt() != null) throw policy.conflict();
            return new OrderRecipeSnapshotResponse(orderId, lineId, RecipeSnapshotStatus.NOT_SUBMITTED,
                    null, null, null, null, line.getQuantity(), line.getUnit(), null, List.of(), null, null);
        }
        policy.requireMatches(snapshot, line);
        var details = snapshot.getSnapshotStatus() == RecipeSnapshotStatus.CAPTURED
                ? usages.findAllByRestaurantIdAndRecipeSnapshotIdOrderByIngredientId(actor.restaurantId(), snapshot.getId())
                    .stream().map(item -> new OrderRecipeSnapshotResponse.Ingredient(item.getIngredientId(),
                            item.getIngredientSkuSnapshot(), item.getIngredientNameSnapshot(), item.getIngredientUnitSnapshot(),
                            item.getRecipeQuantitySnapshot(), item.getWastePercentSnapshot(), item.isTrackInventorySnapshot(),
                            item.getRequiredQuantity())).toList()
                : List.<OrderRecipeSnapshotResponse.Ingredient>of();
        return new OrderRecipeSnapshotResponse(orderId, lineId, snapshot.getSnapshotStatus(), snapshot.getRecipeId(),
                snapshot.getRecipeVersion(), snapshot.getYieldQuantity(), snapshot.getYieldUnit(),
                snapshot.getOrderQuantitySnapshot(), snapshot.getOrderUnitSnapshot(), snapshot.getCapturedAt(), details, snapshot.getRecipeEnabledSnapshot(), snapshot.getCostSourceSnapshot());
    }
}
