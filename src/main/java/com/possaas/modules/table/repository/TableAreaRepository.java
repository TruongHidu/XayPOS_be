package com.possaas.modules.table.repository;

import com.possaas.modules.table.entity.TableArea;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface TableAreaRepository extends JpaRepository<TableArea, UUID>, JpaSpecificationExecutor<TableArea> {
    Optional<TableArea> findByIdAndRestaurantId(UUID id, UUID restaurantId);
    List<TableArea> findAllByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);
}
