package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscriptionpurchase.config.PaymentWorkerProperties;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short transactions only: claims commit before any settlement work so leases survive a crash. */
@Service
public class PaymentEventClaimService {
    public record Claim(UUID eventId, UUID token) {}

    private final SubscriptionPaymentEventRepository events;
    private final PaymentWorkerProperties worker;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PaymentEventClaimService(SubscriptionPaymentEventRepository events, PaymentWorkerProperties worker,
        AuditService audits, Clock clock, PlatformTransactionManager transactions) {
        this.events = events; this.worker = worker; this.audits = audits; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<Claim> claim() {
        return transaction.execute(tx -> {
            Instant now = now();
            events.recoverExpiredLeases(now);
            List<Claim> claims = new ArrayList<>();
            for (UUID id : events.lockClaimable(now, worker.getBatchSize())) {
                var event = events.findById(id).orElseThrow();
                if (!event.isClaimable()) continue;
                UUID token = UUID.randomUUID();
                event.claim(token, now.plus(worker.getLease()), now);
                claims.add(new Claim(id, token));
            }
            events.flush();
            return claims;
        });
    }

    /** Technical failure: back off, or hand over to an operator once retries are exhausted. */
    public void retryLater(Claim claim, String errorCode) {
        transaction.executeWithoutResult(tx -> {
            var event = events.findForUpdate(claim.eventId()).orElse(null);
            if (event == null || !event.heldBy(claim.token())) return;
            Instant now = now();
            if (!now.isBefore(event.getReceivedAt().plus(worker.getRetryExhaustedAfter()))) {
                event.review(ReviewReason.TECHNICAL_RETRY_EXHAUSTED, now);
                events.flush();
                audits.record(event.getRestaurantId(), null, "SUBSCRIPTION_PAYMENT_REVIEW_REQUIRED", "subscription_payment_events",
                    event.getId(), null, Map.of("state", "REVIEW_REQUIRED", "reviewReason", ReviewReason.TECHNICAL_RETRY_EXHAUSTED.name()), null);
                return;
            }
            event.retryLater(now.plus(worker.backoffAfter(event.getRetryCount())), errorCode, now);
            events.flush();
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
