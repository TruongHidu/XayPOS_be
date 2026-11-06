package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.InventoryReservation;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    interface ReservedQuantity {
        UUID getWarehouseId();
        UUID getIngredientId();
        BigDecimal getQuantity();
    }

    @Query("select r from InventoryReservation r where r.restaurantId=:tenant "
            + "and r.allocationId in :allocations order by r.warehouseId,r.ingredientId,r.id")
    List<InventoryReservation> findAllForAllocations(@Param("tenant") UUID tenant,
            @Param("allocations") Collection<UUID> allocationIds);

    @Query("select r.warehouseId as warehouseId,r.ingredientId as ingredientId,sum(r.quantity) as quantity "
            + "from InventoryReservation r,OrderItemInventoryAllocation a where a.restaurantId=r.restaurantId "
            + "and a.id=r.allocationId and r.restaurantId=:tenant and r.warehouseId in :warehouses "
            + "and r.ingredientId in :ingredients and a.status="
            + "com.possaas.modules.inventory.entity.InventoryAllocationStatus.RESERVED "
            + "group by r.warehouseId,r.ingredientId")
    List<ReservedQuantity> sumReserved(@Param("tenant") UUID tenant,
            @Param("warehouses") Collection<UUID> warehouseIds,
            @Param("ingredients") Collection<UUID> ingredientIds);

    @Query("select count(r)>0 from InventoryReservation r,OrderItemInventoryAllocation a "
            + "where a.restaurantId=r.restaurantId and a.id=r.allocationId and r.restaurantId=:tenant "
            + "and r.ingredientId=:ingredient and a.status="
            + "com.possaas.modules.inventory.entity.InventoryAllocationStatus.RESERVED")
    boolean hasReservedForIngredient(@Param("tenant") UUID tenant, @Param("ingredient") UUID ingredientId);
}
