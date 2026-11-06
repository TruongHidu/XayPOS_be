package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.Warehouse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID>, JpaSpecificationExecutor<Warehouse> {
    Optional<Warehouse> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    Optional<Warehouse> findByIdAndRestaurantIdAndDeletedAtIsNull(UUID id, UUID restaurantId);

    Optional<Warehouse> findByRestaurantIdAndDefaultWarehouseTrueAndDeletedAtIsNull(UUID restaurantId);

    List<Warehouse> findAllByRestaurantIdAndDeletedAtIsNull(UUID restaurantId);

    boolean existsByRestaurantIdAndDeletedAtIsNull(UUID restaurantId);

    boolean existsByRestaurantIdAndActiveTrueAndDeletedAtIsNullAndIdNot(UUID restaurantId, UUID id);
}
