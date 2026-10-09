package com.possaas.modules.order.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name="order_item_status_history")
public class OrderItemStatusHistory {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="order_item_id",nullable=false,updatable=false) private UUID orderItemId;
    @Enumerated(EnumType.STRING) @Column(name="from_status",length=20,updatable=false) private OrderItemStatus fromStatus;
    @Enumerated(EnumType.STRING) @Column(name="to_status",nullable=false,length=20,updatable=false) private OrderItemStatus toStatus;
    @Column(name="changed_by",updatable=false) private UUID changedBy;
    @Column(updatable=false) private String note;
    @Column(name="changed_at",nullable=false,updatable=false) private Instant changedAt;
    @Column(name="change_sequence",nullable=false,updatable=false) private long changeSequence;
}
