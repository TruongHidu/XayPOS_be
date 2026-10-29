package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItemRecipeUsage;
import java.util.List;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;

public interface OrderItemRecipeUsageRepository extends JpaRepository<OrderItemRecipeUsage, UUID> {
    List<OrderItemRecipeUsage> findAllByRestaurantIdAndRecipeSnapshotIdOrderByIngredientId(UUID tenant, UUID snapshotId);
    List<OrderItemRecipeUsage> findAllByRestaurantIdAndRecipeSnapshotIdInOrderByRecipeSnapshotIdAscIngredientIdAsc(
            UUID tenant, Collection<UUID> snapshotIds);

    interface FrozenCost {
        UUID getOrderId(); UUID getOrderItemId(); UUID getRecipeSnapshotId(); UUID getRecipeUsageId(); UUID getIngredientId();
        String getUnit(); BigDecimal getQuantity(); Short getCostSnapshotVersion(); Boolean getCostConfigured(); BigDecimal getUnitCost();
    }
    @Query("select l.orderId as orderId,s.orderItemId as orderItemId,s.id as recipeSnapshotId,u.id as recipeUsageId,"
        + "u.ingredientId as ingredientId,u.ingredientUnitSnapshot as unit,u.requiredQuantity as quantity,"
        + "s.costSnapshotVersion as costSnapshotVersion,u.costConfiguredSnapshot as costConfigured,u.unitCostSnapshot as unitCost "
        + "from OrderItemRecipeUsage u join OrderItemRecipeSnapshot s on s.id=u.recipeSnapshotId and s.restaurantId=u.restaurantId "
        + "join OrderItem l on l.id=s.orderItemId and l.restaurantId=s.restaurantId "
        + "where u.restaurantId=:tenant and u.id in :ids")
    List<FrozenCost> findFrozenCosts(@Param("tenant") UUID tenant,@Param("ids") Collection<UUID> ids);
}
