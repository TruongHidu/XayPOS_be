package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscriptionpurchase.application.port.VerifiedPaymentEvent;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentEvent;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores verified provider transactions durably and idempotently. It never matches money to purchases:
 * the caller acknowledges the provider only after this commit returns.
 */
@Slf4j
@Service
public class PaymentInboxService {
    private final SubscriptionPaymentEventRepository events;
    private final PaymentReferenceExtractor references;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public record Receipt(UUID eventId, boolean duplicate) {}

    public PaymentInboxService(SubscriptionPaymentEventRepository events, PaymentReferenceExtractor references,
        AuditService audits, Clock clock, PlatformTransactionManager transactions) {
        this.events = events; this.references = references; this.audits = audits; this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Receipt receive(VerifiedPaymentEvent source) {
        try {
            return transaction.execute(tx -> store(source));
        } catch (RuntimeException failure) {
            // A concurrent delivery of the same transaction won the insert; it is durable now.
            if (!PurchaseDbFailures.uniqueViolation(failure)) throw failure;
            return transaction.execute(tx -> existing(source).map(e -> duplicate(e, source))
                .orElseThrow(() -> failure));
        }
    }

    private Receipt store(VerifiedPaymentEvent source) {
        var previous = existing(source);
        if (previous.isPresent()) return duplicate(previous.get(), source);
        Instant now = now();
        var extraction = references.extract(source.code(), source.content());
        var event = SubscriptionPaymentEvent.receive(source, extraction.reference(), now);
        events.saveAndFlush(event);
        audits.record(null, null, "SUBSCRIPTION_PAYMENT_EVENT_RECEIVED", "subscription_payment_events", event.getId(), null,
            Map.of("provider", source.provider(), "direction", source.direction(), "amount", source.amount(),
                "paymentReference", extraction.reference() == null ? "" : extraction.reference()), null);
        return new Receipt(event.getId(), false);
    }

    private Optional<SubscriptionPaymentEvent> existing(VerifiedPaymentEvent source) {
        Optional<SubscriptionPaymentEvent> byWebhook = source.providerWebhookId() == null ? Optional.empty()
            : events.findByProviderAndProviderAccountKeyAndProviderWebhookId(source.provider(), source.providerAccountKey(), source.providerWebhookId());
        return byWebhook.isPresent() ? byWebhook
            : events.findByProviderAndProviderAccountKeyAndProviderTransactionId(source.provider(), source.providerAccountKey(), source.providerTransactionId());
    }

    private Receipt duplicate(SubscriptionPaymentEvent event, VerifiedPaymentEvent source) {
        if (!event.sameMoney(source)) {
            // Same provider identity but different money: keep the original evidence untouched.
            log.warn("SePay identity conflict on event {}", event.getId());
            audits.record(event.getRestaurantId(), null, "SUBSCRIPTION_PAYMENT_EVENT_IDENTITY_CONFLICT", "subscription_payment_events",
                event.getId(), null, Map.of("providerTransactionId", source.providerTransactionId()), null);
            return new Receipt(event.getId(), true);
        }
        event.recordDelivery(source.providerWebhookId(), now());
        events.flush();
        return new Receipt(event.getId(), true);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
