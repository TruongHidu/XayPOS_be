package com.possaas.modules.order.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Successful append request; no customer data and no mutable response snapshot. */
@Entity @Table(name="order_item_submissions") @Getter @NoArgsConstructor
public class OrderItemSubmission {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="order_id",nullable=false,updatable=false) private UUID orderId;
    @Column(name="actor_user_id",nullable=false,updatable=false) private UUID actorUserId;
    @Column(name="idempotency_key",nullable=false,updatable=false,length=100) private String idempotencyKey;
    @Column(name="request_hash",nullable=false,updatable=false,length=64) private String requestHash;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;

    public OrderItemSubmission(UUID tenant,UUID orderId,UUID actor,String key,String hash,Instant now) {
        this.restaurantId=tenant; this.orderId=orderId; this.actorUserId=actor;
        this.idempotencyKey=key; this.requestHash=hash; this.createdAt=now;
    }
}
