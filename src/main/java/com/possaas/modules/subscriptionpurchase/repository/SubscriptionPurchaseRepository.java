package com.possaas.modules.subscriptionpurchase.repository;

import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface SubscriptionPurchaseRepository extends JpaRepository<SubscriptionPurchase, UUID> {
    Optional<SubscriptionPurchase> findByRestaurantIdAndIdempotencyKey(UUID restaurantId, String idempotencyKey);
    Optional<SubscriptionPurchase> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    @Query("select p from SubscriptionPurchase p where p.restaurantId=:tenant and p.status in ("
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.PENDING_PAYMENT,"
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.PAID,"
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.REVIEW_REQUIRED)")
    Optional<SubscriptionPurchase> findOpen(@Param("tenant") UUID tenant);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SubscriptionPurchase p where p.restaurantId=:tenant and p.status in ("
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.PENDING_PAYMENT,"
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.PAID,"
        + "com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus.REVIEW_REQUIRED)")
    Optional<SubscriptionPurchase> findOpenForUpdate(@Param("tenant") UUID tenant);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SubscriptionPurchase p where p.id=:id and p.restaurantId=:tenant")
    Optional<SubscriptionPurchase> findForUpdate(@Param("id") UUID id, @Param("tenant") UUID tenant);

    @Query(value = """
        select * from subscription_purchases where restaurant_id=:tenant
        and (cast(:status as varchar) is null or status=cast(:status as varchar))
        and (cast(:fromTime as timestamptz) is null or created_at >= cast(:fromTime as timestamptz))
        and (cast(:toTime as timestamptz) is null or created_at < cast(:toTime as timestamptz))
        order by created_at desc,id desc
        """, countQuery = """
        select count(*) from subscription_purchases where restaurant_id=:tenant
        and (cast(:status as varchar) is null or status=cast(:status as varchar))
        and (cast(:fromTime as timestamptz) is null or created_at >= cast(:fromTime as timestamptz))
        and (cast(:toTime as timestamptz) is null or created_at < cast(:toTime as timestamptz))
        """, nativeQuery = true)
    Page<SubscriptionPurchase> search(@Param("tenant") UUID tenant, @Param("status") String status,
        @Param("fromTime") Instant fromTime, @Param("toTime") Instant toTime, Pageable pageable);

    @Query(value = "select count(*) from subscription_payment_events where restaurant_id=:tenant and state='REVIEW_REQUIRED'", nativeQuery = true)
    long countPaymentReviews(@Param("tenant") UUID tenant);

    /** Unmanaged routing read: lets callers prepare payment instructions before taking any lock. */
    Optional<PaymentTerms> findTermsByIdAndRestaurantId(UUID id, UUID restaurantId);

    @Query(value = """
        select id as "id", restaurant_id as "restaurantId" from subscription_purchases
        where status='PENDING_PAYMENT' and expires_at <= :now order by expires_at, id limit :limit
        """, nativeQuery = true)
    List<DueRef> findDue(@Param("now") Instant now, @Param("limit") int limit);

    /** Scalar routing read; does not put a purchase entity into the persistence context before it is locked. */
    @Query("select p.restaurantId from SubscriptionPurchase p where p.id=:id")
    Optional<UUID> findRestaurantId(@Param("id") UUID id);

    /** Activation claim: PAID purchases due for (re)activation whose lease is free. */
    @Query(value = """
        select id from subscription_purchases
        where status='PAID' and activation_next_at <= :now and (activation_lease_until is null or activation_lease_until < :now)
        order by activation_next_at, id limit :limit for update skip locked
        """, nativeQuery = true)
    List<UUID> lockActivationDue(@Param("now") Instant now, @Param("limit") int limit);

    interface PaymentTerms { java.math.BigDecimal getAmount(); String getCurrencyCode(); Instant getExpiresAt(); }
    interface DueRef { UUID getId(); UUID getRestaurantId(); }
}
