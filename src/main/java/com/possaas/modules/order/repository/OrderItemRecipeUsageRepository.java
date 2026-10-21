package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItemRecipeUsage;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRecipeUsageRepository extends JpaRepository<OrderItemRecipeUsage, UUID> {
    List<OrderItemRecipeUsage> findAllByRestaurantIdAndRecipeSnapshotIdOrderByIngredientId(UUID tenant, UUID snapshotId);
    List<OrderItemRecipeUsage> findAllByRestaurantIdAndRecipeSnapshotIdInOrderByRecipeSnapshotIdAscIngredientIdAsc(
            UUID tenant, Collection<UUID> snapshotIds);
}
