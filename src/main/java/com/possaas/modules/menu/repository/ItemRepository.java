package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.entity.*;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {
    @Query("select i from Item i where i.restaurantId=:tenant and i.itemType=com.possaas.modules.menu.entity.ItemType.INGREDIENT "
        + "and i.trackInventory=true and i.deletedAt is null order by i.id")
    List<Item> findAllTrackedIngredients(@Param("tenant") UUID tenant);
    Optional<Item> findByIdAndRestaurantIdAndItemType(UUID id, UUID restaurantId, ItemType type);
    List<Item> findAllByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.restaurantId=:restaurantId and i.id in :ingredientIds "
            + "and i.itemType=com.possaas.modules.menu.entity.ItemType.INGREDIENT order by i.id")
    List<Item> findIngredientsForUpdate(@Param("restaurantId") UUID restaurantId,
            @Param("ingredientIds") Collection<UUID> ingredientIds);

    interface IngredientReferenceCost {
        UUID getIngredientId();
        String getBaseUnit();
        BigDecimal getUnitCost();
    }

    @Query("select i.id as ingredientId, i.baseUnit as baseUnit, i.costPrice as unitCost from Item i "
            + "where i.restaurantId=:restaurantId and i.id in :ingredientIds "
            + "and i.itemType=com.possaas.modules.menu.entity.ItemType.INGREDIENT")
    List<IngredientReferenceCost> findIngredientReferenceCosts(@Param("restaurantId") UUID restaurantId,
            @Param("ingredientIds") Collection<UUID> ingredientIds);

    boolean existsByRestaurantIdAndGroupIdAndDeletedAtIsNull(UUID restaurantId, UUID groupId);
}
