package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.StockDocument;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockDocumentRepository extends JpaRepository<StockDocument, UUID> {
    Optional<StockDocument> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    Optional<StockDocument> findByRestaurantIdAndIdempotencyKey(UUID restaurantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from StockDocument d where d.id=:id and d.restaurantId=:restaurantId")
    Optional<StockDocument> findForUpdate(@Param("restaurantId") UUID restaurantId, @Param("id") UUID id);
}
