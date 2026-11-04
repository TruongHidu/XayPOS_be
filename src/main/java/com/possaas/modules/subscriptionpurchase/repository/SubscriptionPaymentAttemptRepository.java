package com.possaas.modules.subscriptionpurchase.repository;

import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentAttempt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionPaymentAttemptRepository extends JpaRepository<SubscriptionPaymentAttempt, UUID> {
    String UNIQUE_IDEMPOTENCY = "uq_spa_idempotency";
    String UNIQUE_PENDING = "uq_spa_pending";
    String UNIQUE_REFERENCE = "subscription_payment_attempts_reference_code_key";

    Optional<SubscriptionPaymentAttempt> findByRestaurantIdAndIdempotencyKey(UUID restaurantId, String idempotencyKey);

    Optional<SubscriptionPaymentAttempt> findFirstByRestaurantIdAndPurchaseIdOrderByCreatedAtDescIdDesc(UUID restaurantId, UUID purchaseId);

    Optional<SubscriptionPaymentAttempt> findByReferenceCode(String referenceCode);

    /** Caller owns restaurant then purchase locks; attempts are locked in id order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SubscriptionPaymentAttempt a where a.restaurantId=:tenant and a.purchaseId=:purchase order by a.id")
    List<SubscriptionPaymentAttempt> lockAll(@Param("tenant") UUID tenant, @Param("purchase") UUID purchase);

    /** Caller owns restaurant then purchase locks. */
    @Query(value = "select id from subscription_payment_attempts where restaurant_id=:tenant and purchase_id=:purchase and status='PENDING' order by id for update", nativeQuery = true)
    List<UUID> lockPending(@Param("tenant") UUID tenant, @Param("purchase") UUID purchase);

    @Modifying
    @Query(value = """
        update subscription_payment_attempts set status=:target,updated_at=:now,version=version+1
        where restaurant_id=:tenant and purchase_id=:purchase and status='PENDING'
        """, nativeQuery = true)
    int endPending(@Param("tenant") UUID tenant, @Param("purchase") UUID purchase,
        @Param("target") String target, @Param("now") Instant now);
}
