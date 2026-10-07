package com.possaas.modules.table.repository;

import com.possaas.modules.table.entity.RestaurantTable;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID>, JpaSpecificationExecutor<RestaurantTable> {
    Optional<RestaurantTable> findByQrToken(String qrToken);
    Optional<RestaurantTable> findByIdAndRestaurantId(UUID id, UUID restaurantId);
    List<RestaurantTable> findAllByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);
    boolean existsByRestaurantIdAndAreaIdAndDeletedAtIsNull(UUID restaurantId, UUID areaId);
}
