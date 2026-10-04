package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.entity.ItemGroup;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface ItemGroupRepository extends JpaRepository<ItemGroup, UUID>, JpaSpecificationExecutor<ItemGroup> {
    Optional<ItemGroup> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    List<ItemGroup> findAllByRestaurantIdAndIdIn(UUID restaurantId, Collection<UUID> ids);
}
