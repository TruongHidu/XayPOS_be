package com.possaas.modules.order.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity @Table(name="order_items")
public class OrderItem extends OrderRecord {
    @Column(name="order_id",nullable=false,updatable=false) private UUID orderId;
    @Column(name="item_id",updatable=false) private UUID itemId;
    @Column(name="item_name",nullable=false,length=150,updatable=false) private String itemName;
    @Column(nullable=false,length=30,updatable=false) private String unit;
    @Column(nullable=false,precision=12,scale=3) private BigDecimal quantity;
    @Column(name="unit_price",nullable=false,precision=14,scale=2,updatable=false) private BigDecimal unitPrice;
    @Column(name="discount_amount",nullable=false,precision=14,scale=2) private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(name="line_total",nullable=false,precision=14,scale=2) private BigDecimal lineTotal;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private OrderItemStatus status = OrderItemStatus.PENDING;
    private String note;
    @Column(name="sent_to_kitchen_at") private Instant sentToKitchenAt;
    @Column(name="cancel_reason") private String cancelReason;
    @Column(name="line_number",nullable=false,updatable=false) private int lineNumber;
}
