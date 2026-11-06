package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItemSubmission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemSubmissionRepository extends JpaRepository<OrderItemSubmission,UUID> {
    Optional<OrderItemSubmission> findByRestaurantIdAndIdempotencyKey(UUID restaurantId,String key);
}
