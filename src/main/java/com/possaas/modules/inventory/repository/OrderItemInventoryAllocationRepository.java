package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.OrderItemInventoryAllocation;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OrderItemInventoryAllocationRepository extends JpaRepository<OrderItemInventoryAllocation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderItemInventoryAllocation a where a.restaurantId=:tenant "
            + "and a.orderItemId in :items order by a.orderItemId")
    List<OrderItemInventoryAllocation> findAllForUpdate(@Param("tenant") UUID tenant,
            @Param("items") Collection<UUID> itemIds);

    List<OrderItemInventoryAllocation> findAllByRestaurantIdAndOrderId(UUID restaurantId, UUID orderId);

    List<OrderItemInventoryAllocation> findAllByRestaurantIdAndOrderItemIdIn(UUID restaurantId,
            Collection<UUID> orderItemIds);

    Optional<OrderItemInventoryAllocation> findByRestaurantIdAndOrderIdAndOrderItemId(
            UUID restaurantId, UUID orderId, UUID orderItemId);

    @Query("select count(a)>0 from OrderItemInventoryAllocation a where a.restaurantId=:tenant "
            + "and a.warehouseId=:warehouse and a.status="
            + "com.possaas.modules.inventory.entity.InventoryAllocationStatus.RESERVED")
    boolean hasReservedInWarehouse(@Param("tenant") UUID tenant, @Param("warehouse") UUID warehouseId);
}
