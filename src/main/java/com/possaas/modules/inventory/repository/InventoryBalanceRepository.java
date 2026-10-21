package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.InventoryBalance;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {
    interface IngredientTotalQuantity {
        UUID getItemId();
        BigDecimal getQuantity();
    }

    // Global Item.costPrice requires the physical quantity across ALL tenant warehouses.
    @Query("select b.itemId as itemId, sum(b.quantity) as quantity from InventoryBalance b "
            + "where b.restaurantId=:restaurantId and b.itemId in :itemIds group by b.itemId")
    List<IngredientTotalQuantity> findTotalQuantities(@Param("restaurantId") UUID restaurantId,
            @Param("itemIds") Collection<UUID> itemIds);

    Optional<InventoryBalance> findByRestaurantIdAndWarehouseIdAndItemId(
            UUID restaurantId, UUID warehouseId, UUID itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBalance b where b.restaurantId=:restaurantId "
            + "and b.warehouseId=:warehouseId and b.itemId=:itemId")
    Optional<InventoryBalance> findForUpdate(@Param("restaurantId") UUID restaurantId,
            @Param("warehouseId") UUID warehouseId, @Param("itemId") UUID itemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBalance b where b.restaurantId=:restaurantId "
            + "and b.warehouseId=:warehouseId and b.itemId in :itemIds order by b.itemId")
    List<InventoryBalance> findAllForUpdate(@Param("restaurantId") UUID restaurantId,
            @Param("warehouseId") UUID warehouseId, @Param("itemIds") Collection<UUID> itemIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBalance b where b.restaurantId=:tenant "
            + "and b.warehouseId in :warehouses and b.itemId in :ingredients order by b.warehouseId,b.itemId")
    List<InventoryBalance> findAllInWarehousesForUpdate(@Param("tenant") UUID tenant,
            @Param("warehouses") Collection<UUID> warehouseIds,
            @Param("ingredients") Collection<UUID> ingredientIds);

    List<InventoryBalance> findAllByRestaurantIdAndWarehouseIdInAndItemIdIn(UUID restaurantId,
            Collection<UUID> warehouseIds, Collection<UUID> itemIds);

    @Query("select count(b) > 0 from InventoryBalance b where b.restaurantId=:restaurantId "
            + "and b.warehouseId=:warehouseId and b.quantity <> :zero")
    boolean hasNonZeroStockInWarehouse(@Param("restaurantId") UUID restaurantId,
            @Param("warehouseId") UUID warehouseId, @Param("zero") BigDecimal zero);

    @Query("select count(b) > 0 from InventoryBalance b where b.restaurantId=:restaurantId "
            + "and b.itemId=:itemId and b.quantity <> :zero")
    boolean hasNonZeroStockForItem(@Param("restaurantId") UUID restaurantId,
            @Param("itemId") UUID itemId, @Param("zero") BigDecimal zero);
}
