package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItemStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemStatusHistoryRepository extends JpaRepository<OrderItemStatusHistory,UUID> {
    List<OrderItemStatusHistory> findAllByRestaurantIdAndOrderItemIdOrderByChangeSequenceAsc(UUID tenant,UUID itemId);
}
