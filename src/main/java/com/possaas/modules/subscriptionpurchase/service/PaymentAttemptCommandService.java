package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionProvider;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionRequest;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentProviderUnavailableException;
import com.possaas.modules.subscriptionpurchase.application.port.PreparedPaymentInstructions;
import com.possaas.modules.subscriptionpurchase.dto.PaymentAttemptResponses;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentAttempt;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPaymentAttemptRepository;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the payable attempt of a purchase. Instructions are prepared before any lock (a provider may become remote),
 * then revalidated under restaurant → purchase → attempts locks.
 */
@Service
public class PaymentAttemptCommandService {
    private static final int MAX_ROUNDS = 3;
    private final PurchaseAccessPolicy access;
    private final PaymentAttemptAvailability availability;
    private final PaymentInstructionProvider provider;
    private final PaymentReferenceGenerator references;
    private final PurchaseExpirationService expiration;
    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final PaymentAttemptResponseMapper mapper;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate commandTransaction;

    public PaymentAttemptCommandService(PurchaseAccessPolicy access, PaymentAttemptAvailability availability,
        PaymentInstructionProvider provider, PaymentReferenceGenerator references, PurchaseExpirationService expiration,
        SubscriptionPurchaseRepository purchases, SubscriptionPaymentAttemptRepository attempts,
        PaymentAttemptResponseMapper mapper, AuditService audits, Clock clock, PlatformTransactionManager transactions) {
        this.access=access; this.availability=availability; this.provider=provider; this.references=references;
        this.expiration=expiration; this.purchases=purchases; this.attempts=attempts; this.mapper=mapper;
        this.audits=audits; this.clock=clock;
        commandTransaction = new TransactionTemplate(transactions);
        commandTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Application boundary: no caller-held lock; every round starts a fresh DB transaction. */
    public PaymentAttemptResponses.Creation create(UUID purchaseId, String key, String ip) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.CREATE);
        if (purchaseId == null || key == null || !key.matches("[A-Za-z0-9._:-]{8,100}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid idempotency key");
        UUID tenant = actor.restaurantId();
        String hash = hash(purchaseId);
        var previous = attempts.findByRestaurantIdAndIdempotencyKey(tenant, key);
        if (previous.isPresent()) return replay(previous.get(), hash);
        if (!availability.acceptingNew()) throw unavailable("PURCHASE_UNAVAILABLE");
        for (int round = 0; round < MAX_ROUNDS; round++) {
            var terms = purchases.findTermsByIdAndRestaurantId(purchaseId, tenant).orElseThrow(PaymentAttemptCommandService::notFound);
            String reference = references.next();
            PreparedPaymentInstructions prepared = prepare(reference, terms);
            try {
                Outcome outcome = commandTransaction.execute(tx -> createLocked(actor, purchaseId, key, hash, reference, terms, prepared, ip));
                if (outcome.expired()) throw conflict("PURCHASE_PERIOD_EXPIRED");
                if (!outcome.stale()) return outcome.creation();
            } catch (RuntimeException failure) {
                if (failure instanceof BusinessException) throw failure;
                if (PurchaseDbFailures.violates(failure, SubscriptionPaymentAttemptRepository.UNIQUE_IDEMPOTENCY)) {
                    var winner = attempts.findByRestaurantIdAndIdempotencyKey(tenant, key);
                    if (winner.isPresent()) return replay(winner.get(), hash);
                }
                if (PurchaseDbFailures.violates(failure, SubscriptionPaymentAttemptRepository.UNIQUE_PENDING))
                    throw conflict("PAYMENT_ATTEMPT_PENDING");
                boolean collision = PurchaseDbFailures.violates(failure, SubscriptionPaymentAttemptRepository.UNIQUE_REFERENCE);
                if (!collision && !PurchaseDbFailures.retryable(failure)) {
                    if (failure instanceof DataIntegrityViolationException) throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
                    throw failure;
                }
            }
        }
        throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
    }

    private Outcome createLocked(CurrentUser actor, UUID purchaseId, String key, String hash, String reference,
        SubscriptionPurchaseRepository.PaymentTerms terms, PreparedPaymentInstructions prepared, String ip) {
        UUID tenant = actor.restaurantId();
        access.lockRestaurant(tenant);
        var existing = attempts.findByRestaurantIdAndIdempotencyKey(tenant, key);
        if (existing.isPresent()) return Outcome.of(replay(existing.get(), hash));
        if (!availability.acceptingNew()) throw unavailable("PURCHASE_UNAVAILABLE");
        SubscriptionPurchase purchase = purchases.findForUpdate(purchaseId, tenant).orElseThrow(PaymentAttemptCommandService::notFound);
        Instant now = now();
        // Commit the reconciled expiry; the 409 is raised after commit.
        if (expiration.expireIfDue(purchase, now)) return Outcome.EXPIRED;
        if (purchase.getStatus() == PurchaseStatus.REVIEW_REQUIRED) throw conflict("PURCHASE_REVIEW_REQUIRED");
        if (purchase.getStatus() == PurchaseStatus.EXPIRED) throw conflict("PURCHASE_PERIOD_EXPIRED");
        if (purchase.getStatus() != PurchaseStatus.PENDING_PAYMENT) throw conflict("PAYMENT_ATTEMPT_NOT_ALLOWED");
        if (!sameTerms(purchase, terms)) return Outcome.STALE;
        if (!attempts.lockPending(tenant, purchaseId).isEmpty()) throw conflict("PAYMENT_ATTEMPT_PENDING");
        UUID id = UUID.randomUUID();
        var response = mapper.initial(id, purchase, reference, prepared, now);
        attempts.saveAndFlush(SubscriptionPaymentAttempt.create(id, purchase, reference, prepared, key, hash, mapper.freeze(response), now));
        audits.record(tenant, actor.userId(), "SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED", "subscription_payment_attempts", id, null,
            Map.of("status", "PENDING", "purchaseId", purchaseId.toString(), "referenceCode", reference,
                "amount", response.amount(), "currencyCode", response.currencyCode(), "expiresAt", response.expiresAt().toString()), ip);
        return Outcome.of(new PaymentAttemptResponses.Creation(response, false));
    }

    private PreparedPaymentInstructions prepare(String reference, SubscriptionPurchaseRepository.PaymentTerms terms) {
        if (!provider.isReady()) throw unavailable("PAYMENT_PROVIDER_UNAVAILABLE");
        try {
            var prepared = provider.prepare(new PaymentInstructionRequest(reference, terms.getAmount().longValueExact(), terms.getCurrencyCode()));
            if (prepared == null || prepared.instructions() == null || !reference.equals(prepared.instructions().transferContent()))
                throw new PaymentProviderUnavailableException("Provider returned instructions for another reference");
            return prepared;
        } catch (PaymentProviderUnavailableException | ArithmeticException failure) {
            throw unavailable("PAYMENT_PROVIDER_UNAVAILABLE");
        }
    }

    /** Instructions were prepared from an unlocked read; the locked row must still carry the same money. */
    private static boolean sameTerms(SubscriptionPurchase purchase, SubscriptionPurchaseRepository.PaymentTerms terms) {
        return purchase.getAmount().compareTo(terms.getAmount()) == 0 && purchase.getCurrencyCode().equals(terms.getCurrencyCode())
            && purchase.getExpiresAt().equals(terms.getExpiresAt());
    }

    private PaymentAttemptResponses.Creation replay(SubscriptionPaymentAttempt attempt, String hash) {
        if (!attempt.getRequestHash().equals(hash)) throw conflict("IDEMPOTENCY_KEY_REUSED");
        return new PaymentAttemptResponses.Creation(mapper.replay(attempt.getCreationResponse()), true);
    }

    private record Outcome(PaymentAttemptResponses.Creation creation, boolean expired, boolean stale) {
        static final Outcome EXPIRED = new Outcome(null, true, false);
        static final Outcome STALE = new Outcome(null, false, true);
        static Outcome of(PaymentAttemptResponses.Creation creation) { return new Outcome(creation, false, false); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static ResourceNotFoundException notFound() { return new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found"); }
    private static BusinessException conflict(String code) { return new BusinessException(HttpStatus.CONFLICT, code, "Payment attempt conflicts with current state"); }
    private static BusinessException unavailable(String code) { return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, code, "Payment attempts are unavailable"); }
    private static String hash(UUID purchaseId) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("{\"purchaseId\":\"" + purchaseId + "\"}").getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
