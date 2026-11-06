package com.possaas.modules.subscriptionpurchase.repository;

import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentEvent;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionPaymentEventRepository extends JpaRepository<SubscriptionPaymentEvent, UUID> {
    Optional<SubscriptionPaymentEvent> findByProviderAndProviderAccountKeyAndProviderWebhookId(String provider, String accountKey, Long webhookId);

    Optional<SubscriptionPaymentEvent> findByProviderAndProviderAccountKeyAndProviderTransactionId(String provider, String accountKey, String transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SubscriptionPaymentEvent e where e.id=:id")
    Optional<SubscriptionPaymentEvent> findForUpdate(@Param("id") UUID id);

    /** Worker claim: rows locked by another worker are skipped instead of waited on. */
    @Query(value = """
        select id from subscription_payment_events
        where state in ('RECEIVED','RETRY_PENDING') and (next_attempt_at is null or next_attempt_at <= :now)
        order by id limit :limit for update skip locked
        """, nativeQuery = true)
    List<UUID> lockClaimable(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
        update subscription_payment_events set state='RETRY_PENDING', lease_until=null, processing_token=null,
            next_attempt_at=:now, last_error_code='LEASE_EXPIRED', updated_at=:now, version=version+1
        where state='PROCESSING' and lease_until < :now
        """, nativeQuery = true)
    int recoverExpiredLeases(@Param("now") Instant now);
}
