package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.domain.SubscriptionValidityPolicy;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.subscription.service.SubscriptionExpirationService;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PurchaseCommandService {
    private final PurchaseAccessPolicy access;
    private final PurchaseProperties settings;
    private final PurchaseEligibilityPolicy eligibility;
    private final PurchaseQuoteFactory quotes;
    private final PurchaseExpirationService expiration;
    private final SubscriptionPurchaseRepository purchases;
    private final SubscriptionPaymentAttemptRepository attempts;
    private final RestaurantSubscriptionRepository subscriptions;
    private final SubscriptionExpirationService subscriptionExpiration;
    private final SubscriptionValidityPolicy validity;
    private final PurchaseResponseMapper mapper;
    private final AuditService audits;
    private final Clock clock;
    private final TransactionTemplate createTransaction;
    private final TransactionTemplate commandTransaction;

    public PurchaseCommandService(PurchaseAccessPolicy access, PurchaseProperties settings, PurchaseEligibilityPolicy eligibility,
        PurchaseQuoteFactory quotes, PurchaseExpirationService expiration, SubscriptionPurchaseRepository purchases,
        SubscriptionPaymentAttemptRepository attempts,
        RestaurantSubscriptionRepository subscriptions, SubscriptionExpirationService subscriptionExpiration,
        SubscriptionValidityPolicy validity, PurchaseResponseMapper mapper, AuditService audits, Clock clock,
        PlatformTransactionManager transactions) {
        this.access=access; this.settings=settings; this.eligibility=eligibility; this.quotes=quotes; this.expiration=expiration;
        this.purchases=purchases; this.attempts=attempts; this.subscriptions=subscriptions; this.subscriptionExpiration=subscriptionExpiration;
        this.validity=validity; this.mapper=mapper; this.audits=audits; this.clock=clock;
        createTransaction = new TransactionTemplate(transactions);
        createTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        createTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        commandTransaction = new TransactionTemplate(transactions);
        commandTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Application boundary: no caller-held restaurant lock; every retry starts a fresh DB transaction. */
    public PurchaseResponses.Creation create(PurchaseRequests.Create request, String key, String ip) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.CREATE);
        if (request == null || request.packageCode() == null || request.packageCode().length() > 50
            || !request.packageCode().matches("\\s*[A-Za-z0-9_-]+\\s*") || key == null || !key.matches("[A-Za-z0-9._:-]{8,100}"))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid package code or idempotency key");
        String code = request.packageCode().trim().toUpperCase(Locale.ROOT);
        String hash = hash(code);
        var previous = purchases.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(), key);
        if (previous.isPresent()) return replay(previous.get(), hash);
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return createTransaction.execute(tx -> createLocked(actor, code, key, hash, ip)); }
            catch (RuntimeException failure) {
                if (failure instanceof BusinessException) throw failure;
                if (PurchaseDbFailures.uniqueViolation(failure)) {
                    var winner = purchases.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(), key);
                    if (winner.isPresent()) return replay(winner.get(), hash);
                    var open = purchases.findOpen(actor.restaurantId());
                    if (open.isPresent()) throw conflict(open.get().getStatus() == PurchaseStatus.REVIEW_REQUIRED
                        ? "PURCHASE_REVIEW_REQUIRED" : "PURCHASE_IN_PROGRESS");
                }
                if (failure instanceof DataIntegrityViolationException && !PurchaseDbFailures.uniqueViolation(failure))
                    throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
                if (!PurchaseDbFailures.retryable(failure)) throw failure;
                if (attempt == 2) throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
            }
        }
        throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
    }

    private PurchaseResponses.Creation createLocked(CurrentUser actor, String code, String key, String hash, String ip) {
        access.lockRestaurant(actor.restaurantId());
        var existing = purchases.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(), key);
        if (existing.isPresent()) return replay(existing.get(), hash);
        if (!settings.isAcceptNew()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PURCHASE_UNAVAILABLE", "New package purchases are disabled");
        Instant now = now();
        var open = purchases.findOpenForUpdate(actor.restaurantId()).orElse(null);
        if (open != null && expiration.expireIfDue(open, now)) open = null;
        RestaurantSubscription sub = subscriptions.findActiveForUpdate(actor.restaurantId()).orElse(null);
        if (sub != null && subscriptionExpiration.expireIfDue(sub)) sub = null;
        if (sub == null) sub = subscriptions.findByRestaurantIdAndStatus(actor.restaurantId(), SubscriptionStatus.PENDING).orElse(null);
        eligibility.assertEligible(eligibility.blockedReason(true, actor, sub, open, now));
        var quote = quotes.capture(code, now);
        UUID id = UUID.randomUUID();
        var response = mapper.initial(id, quote, actor);
        var purchase = SubscriptionPurchase.create(actor.restaurantId(), actor.userId(), quote, key, hash, id, mapper.freeze(response));
        purchases.saveAndFlush(purchase);
        audits.record(actor.restaurantId(), actor.userId(), "SUBSCRIPTION_PURCHASE_CREATED", "subscription_purchases", id,
            null, Map.of("status", "PENDING_PAYMENT", "packageCode", code, "amount", quote.priceAmount(), "currencyCode", "VND"), ip);
        return new PurchaseResponses.Creation(response, false);
    }

    public PurchaseResponses.View cancel(UUID id, PurchaseRequests.Cancel request, String ip) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.CANCEL);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
            || (request.reason() != null && (request.reason().isBlank() || request.reason().length() > 500)))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid cancellation request");
        try {
            return commandTransaction.execute(tx -> {
                access.lockRestaurant(actor.restaurantId());
                var p = purchases.findForUpdate(id, actor.restaurantId())
                    .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found"));
                Instant now = now();
                if (p.getStatus() != PurchaseStatus.CANCELLED) {
                    if (p.getStatus() == PurchaseStatus.EXPIRED || p.isDue(now)) throw conflict("PURCHASE_PERIOD_EXPIRED");
                    if (p.getStatus() != PurchaseStatus.PENDING_PAYMENT || p.getPaidAt() != null) throw conflict("PURCHASE_CANNOT_CANCEL");
                    if (p.getVersion() != request.expectedVersion()) throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
                    attempts.lockPending(actor.restaurantId(), id);
                    attempts.endPending(actor.restaurantId(), id, "CANCELLED", now);
                    p.cancel(now, request.reason() == null ? null : request.reason().trim());
                    purchases.flush();
                    audits.record(actor.restaurantId(), actor.userId(), "SUBSCRIPTION_PURCHASE_CANCELLED", "subscription_purchases", id,
                        Map.of("status", "PENDING_PAYMENT"), Map.of("status", "CANCELLED"), ip);
                }
                var sub = subscriptions.findForOnboarding(actor.restaurantId(), now).orElse(null);
                var open = purchases.findOpen(actor.restaurantId()).orElse(null);
                boolean canPurchase = eligibility.blockedReason(settings.isAcceptNew(), actor, sub, open, now) == null;
                boolean effective = sub != null && validity.isEffective(sub.getStatus(), sub.getStartAt(), sub.getEndAt(), now);
                return mapper.view(p, actor, canPurchase, effective, now);
            });
        } catch (DataIntegrityViolationException | ConcurrencyFailureException collision) {
            throw conflict("CONCURRENT_SUBSCRIPTION_UPDATE");
        }
    }

    private PurchaseResponses.Creation replay(SubscriptionPurchase p, String hash) {
        if (!p.getRequestHash().equals(hash)) throw conflict("IDEMPOTENCY_KEY_REUSED");
        return new PurchaseResponses.Creation(mapper.replay(p.getCreationResponse()), true);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static BusinessException conflict(String code) { return new BusinessException(HttpStatus.CONFLICT, code, "Purchase command conflicts with current state"); }
    private static String hash(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("{\"packageCode\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
