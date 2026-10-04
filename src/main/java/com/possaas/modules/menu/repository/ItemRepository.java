package com.possaas.modules.menu.repository;

import com.possaas.modules.menu.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {
    Optional<Item> findByIdAndRestaurantIdAndItemType(UUID id, UUID restaurantId, ItemType type);

    boolean existsByRestaurantIdAndGroupIdAndDeletedAtIsNull(UUID restaurantId, UUID groupId);
}
