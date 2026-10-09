package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem,UUID> {
    List<OrderItem> findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(UUID tenant,UUID orderId);
}
