package com.possaas.modules.order.repository;

import com.possaas.modules.order.entity.OrderItem;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import com.possaas.modules.order.dto.KitchenQueueItemResponse;
import com.possaas.modules.order.entity.OrderItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderItemRepository extends JpaRepository<OrderItem,UUID> {
    List<OrderItem> findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(UUID tenant,UUID orderId);
    Optional<OrderItem> findByIdAndRestaurantIdAndOrderId(UUID id,UUID tenant,UUID orderId);

    @Query(value="""
        select new com.possaas.modules.order.dto.KitchenQueueItemResponse(
            o.id, o.orderCode, o.version, line.id, line.itemName, line.quantity,
            line.unit, line.note, line.status, o.serviceType, line.createdAt,
            line.sentToKitchenAt, session.id, physical.id, physical.code, physical.name)
        from OrderItem line join Order o on o.id = line.orderId and o.restaurantId = line.restaurantId
        left join TableSession session on session.id = o.tableSessionId and session.restaurantId = o.restaurantId
        left join RestaurantTable physical on physical.id = session.tableId and physical.restaurantId = session.restaurantId
        where line.restaurantId = :tenant and line.status = :status
          and o.status in (com.possaas.modules.order.entity.OrderStatus.CONFIRMED,
                           com.possaas.modules.order.entity.OrderStatus.PREPARING,
                           com.possaas.modules.order.entity.OrderStatus.READY,
                           com.possaas.modules.order.entity.OrderStatus.SERVED)
        order by coalesce(line.sentToKitchenAt, line.createdAt), line.id
        """, countQuery="""
        select count(line) from OrderItem line join Order o
          on o.id = line.orderId and o.restaurantId = line.restaurantId
        where line.restaurantId = :tenant and line.status = :status
          and o.status in (com.possaas.modules.order.entity.OrderStatus.CONFIRMED,
                           com.possaas.modules.order.entity.OrderStatus.PREPARING,
                           com.possaas.modules.order.entity.OrderStatus.READY,
                           com.possaas.modules.order.entity.OrderStatus.SERVED)
        """)
    Page<KitchenQueueItemResponse> kitchenQueue(@Param("tenant") UUID tenant,
            @Param("status") OrderItemStatus status, Pageable pageable);
}
