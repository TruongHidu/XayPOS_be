package com.possaas.modules.payment.repository;

import com.possaas.modules.payment.entity.Payment;
import java.util.Optional;
import java.util.Collection;
import com.possaas.modules.payment.entity.PaymentStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    boolean existsByRestaurantIdAndOrderIdInAndStatus(UUID restaurantId, Collection<UUID> orderIds, PaymentStatus status);
    Optional<Payment> findByRestaurantIdAndIdempotencyKey(UUID restaurantId, String idempotencyKey);
    Optional<Payment> findByIdAndRestaurantIdAndOrderId(UUID id, UUID restaurantId, UUID orderId);
    Page<Payment> findAllByRestaurantIdAndOrderId(UUID restaurantId, UUID orderId, Pageable pageable);
}
