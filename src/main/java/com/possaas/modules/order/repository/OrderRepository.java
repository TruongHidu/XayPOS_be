package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order,UUID>, JpaSpecificationExecutor<Order> {
    Optional<Order> findByIdAndRestaurantId(UUID id, UUID tenant);
    Optional<Order> findByRestaurantIdAndIdempotencyKey(UUID tenant, String key);
    boolean existsByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(
        UUID tenant, UUID sessionId, ServiceType type, Collection<OrderStatus> statuses);
    List<Order> findAllByRestaurantIdAndTableSessionIdAndServiceTypeAndStatusIn(
        UUID tenant, UUID sessionId, ServiceType type, Collection<OrderStatus> statuses);

    @Query("""
        select count(o) > 0 from Order o where o.restaurantId=:tenant and o.tableSessionId=:session
        and (o.status <> com.possaas.modules.order.entity.OrderStatus.CANCELLED
          or o.paidAmount > 0 or o.paymentStatus <> com.possaas.modules.order.entity.OrderPaymentStatus.UNPAID)
        """)
    boolean hasBlockingSessionOrders(@Param("tenant") UUID tenant,@Param("session") UUID session);
}
