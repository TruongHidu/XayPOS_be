package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.LowStockAlert;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface LowStockAlertRepository extends JpaRepository<LowStockAlert,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LowStockAlert a where a.restaurantId=:tenant and a.id=:id")
    Optional<LowStockAlert> findForUpdate(@Param("tenant") UUID tenant,@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LowStockAlert a where a.restaurantId=:tenant and a.warehouseId in :warehouses "
            + "and a.itemId in :items and a.status in (OPEN,ACKNOWLEDGED) order by a.warehouseId,a.itemId")
    List<LowStockAlert> findUnresolvedForUpdate(@Param("tenant") UUID tenant,
            @Param("warehouses") Collection<UUID> warehouses,@Param("items") Collection<UUID> items);
}
