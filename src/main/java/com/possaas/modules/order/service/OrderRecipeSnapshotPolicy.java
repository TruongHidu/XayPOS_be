package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemRecipeSnapshot;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class OrderRecipeSnapshotPolicy {
    public void requireMatches(OrderItemRecipeSnapshot snapshot, OrderItem line) {
        if (!Objects.equals(snapshot.getRestaurantId(), line.getRestaurantId())
                || !Objects.equals(snapshot.getOrderItemId(), line.getId())
                || !Objects.equals(snapshot.getMenuItemIdSnapshot(), line.getItemId())
                || !Objects.equals(snapshot.getOrderUnitSnapshot(), line.getUnit())
                || snapshot.getOrderQuantitySnapshot().compareTo(line.getQuantity()) != 0)
            throw conflict();
    }

    public ConflictException conflict() {
        return new ConflictException("ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT",
                "Submitted order item differs from its immutable recipe decision or its snapshot is missing");
    }
}
