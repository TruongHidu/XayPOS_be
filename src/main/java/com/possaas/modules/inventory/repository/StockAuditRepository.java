package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface StockAuditRepository extends JpaRepository<StockAudit,UUID> {
    Optional<StockAudit> findByRestaurantIdAndIdempotencyKey(UUID tenant,String key);
    boolean existsByRestaurantIdAndWarehouseIdAndStatus(UUID tenant,UUID warehouse,StockAuditStatus status);
    boolean existsByIdAndRestaurantId(UUID id,UUID tenant);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StockAudit a where a.restaurantId=:tenant and a.id=:id")
    Optional<StockAudit> findForUpdate(@Param("tenant") UUID tenant,@Param("id") UUID id);
}
