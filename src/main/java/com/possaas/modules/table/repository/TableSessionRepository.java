package com.possaas.modules.table.repository;

import com.possaas.modules.table.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TableSessionRepository extends JpaRepository<TableSession, UUID>, JpaSpecificationExecutor<TableSession> {
    Optional<TableSession> findByIdAndRestaurantId(UUID id, UUID restaurantId);
    Optional<TableSession> findByRestaurantIdAndTableIdAndStatus(UUID restaurantId, UUID tableId, TableSessionStatus status);
    List<TableSession> findAllByRestaurantIdAndTableIdAndStatus(UUID restaurantId, UUID tableId, TableSessionStatus status);
    boolean existsByRestaurantIdAndTableIdAndStatus(UUID restaurantId, UUID tableId, TableSessionStatus status);
    List<TableSession> findAllByRestaurantIdAndTableIdInAndStatus(UUID restaurantId, Collection<UUID> tableIds, TableSessionStatus status);

    @Query("""
        select count(s) > 0 from TableSession s, RestaurantTable t
        where s.restaurantId = :tenant and t.restaurantId = :tenant
          and s.tableId = t.id and t.areaId = :area
          and s.status = com.possaas.modules.table.entity.TableSessionStatus.OPEN
        """)
    boolean hasOpenSessionsInArea(@Param("tenant") UUID tenant, @Param("area") UUID area);
}
