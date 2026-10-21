package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItemRecipeSnapshot;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRecipeSnapshotRepository extends JpaRepository<OrderItemRecipeSnapshot, UUID> {
    List<OrderItemRecipeSnapshot> findAllByRestaurantIdAndOrderItemIdIn(UUID tenant, Collection<UUID> lineIds);
    Optional<OrderItemRecipeSnapshot> findByRestaurantIdAndOrderItemId(UUID tenant, UUID lineId);
}
