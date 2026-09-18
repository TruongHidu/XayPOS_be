package com.possaas.modules.subscription.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.audit.service.AuditRecordCommand;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionExpirationService {
    private final RestaurantSubscriptionRepository subscriptionRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Value("${app.jobs.subscription-expiration.batch-size:100}")
    private int configuredBatchSize;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfDue(RestaurantSubscription subscription) {
        boolean expired = transitionIfDue(subscription, clock.instant());
        if (expired) {
            subscriptionRepository.flush();
            recordExpiration(subscription);
        }
        return expired;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcileByIdIfDue(UUID restaurantId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndRestaurantIdForUpdate(subscriptionId, restaurantId)
            .map(subscription -> {
                boolean expired = transitionIfDue(subscription, clock.instant());
                if (expired) {
                    recordExpiration(subscription);
                }
                return expired;
            })
            .orElse(false);
    }

    @Transactional
    public int expireDueBatch() {
        int batchSize = Math.min(Math.max(configuredBatchSize, 1), 1_000);
        Instant now = clock.instant();
        List<RestaurantSubscription> due = subscriptionRepository.findDueActiveForUpdate(now, batchSize);
        List<AuditRecordCommand> auditRecords = new ArrayList<>(due.size());
        int expired = 0;
        for (RestaurantSubscription subscription : due) {
            if (transitionIfDue(subscription, now)) {
                expired++;
                auditRecords.add(expirationRecord(subscription));
            }
        }
        subscriptionRepository.flush();
        auditService.recordAll(auditRecords);
        return expired;
    }

    private boolean transitionIfDue(RestaurantSubscription subscription, Instant now) {
        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
            || subscription.getEndAt().isAfter(now)) {
            return false;
        }
        subscription.setStatus(SubscriptionStatus.EXPIRED);
        return true;
    }

    private void recordExpiration(RestaurantSubscription subscription) {
        AuditRecordCommand record = expirationRecord(subscription);
        auditService.record(
            record.restaurantId(),
            record.actorUserId(),
            record.actionCode(),
            record.entityType(),
            record.entityId(),
            record.beforeData(),
            record.afterData(),
            record.ipAddress()
        );
    }

    private static AuditRecordCommand expirationRecord(RestaurantSubscription subscription) {
        return new AuditRecordCommand(
            subscription.getRestaurantId(),
            null,
            "SUBSCRIPTION_EXPIRED",
            "restaurant_subscriptions",
            subscription.getId(),
            Map.of("status", SubscriptionStatus.ACTIVE.name()),
            Map.of("status", SubscriptionStatus.EXPIRED.name()),
            null
        );
    }
}
