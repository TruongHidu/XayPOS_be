package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.StockDocumentItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockDocumentItemRepository extends JpaRepository<StockDocumentItem, UUID> {
    List<StockDocumentItem> findAllByRestaurantIdAndStockDocumentIdOrderByCreatedAtAscIdAsc(
            UUID restaurantId, UUID documentId);

    Optional<StockDocumentItem> findByIdAndRestaurantIdAndStockDocumentId(
            UUID id, UUID restaurantId, UUID documentId);

    boolean existsByRestaurantIdAndStockDocumentIdAndIngredientId(
            UUID restaurantId, UUID documentId, UUID ingredientId);
}
