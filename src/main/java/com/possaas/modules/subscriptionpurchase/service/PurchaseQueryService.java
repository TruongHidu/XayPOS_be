package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscription.domain.SubscriptionValidityPolicy;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import com.possaas.modules.subscriptionpurchase.repository.SubscriptionPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class PurchaseQueryService {
    private final PurchaseAccessPolicy access;
    private final PurchaseProperties settings;
    private final PurchaseEligibilityPolicy eligibility;
    private final SubscriptionPurchaseRepository purchases;
    private final RestaurantSubscriptionRepository subscriptions;
    private final PackagePlanRepository packages;
    private final SubscriptionValidityPolicy validity;
    private final PurchaseResponseMapper mapper;
    private final Clock clock;

    public PurchaseResponses.Onboarding onboarding() {
        CurrentUser actor = access.require(PurchaseAccessPolicy.VIEW);
        Instant now = now(); Facts facts = facts(actor, now);
        RestaurantSubscription sub = facts.subscription();
        String state = sub == null ? "NONE" : sub.getStatus() == SubscriptionStatus.ACTIVE && !sub.getEndAt().isAfter(now)
            ? "EXPIRED" : sub.getStatus().name();
        PurchaseResponses.Subscription summary = sub == null ? null : new PurchaseResponses.Subscription(sub.getId(),
            sub.getStatus().name(), facts.effective(), packages.findById(sub.getPackageId()).map(p -> p.getCode()).orElse(null),
            sub.getStartAt(), sub.getEndAt(), sub.getActivatedAt());
        var current = facts.open() == null ? null : mapper.view(facts.open(), actor, facts.canPurchase(), facts.effective(), now);
        List<String> actions = facts.canPurchase() ? List.of("CREATE_PURCHASE") : current != null ? current.allowedActions()
            : facts.effective() ? List.of("OPEN_APP") : List.of();
        return new PurchaseResponses.Onboarding(state, summary, current, purchases.countPaymentReviews(actor.restaurantId()),
            facts.canPurchase(), facts.blockedReason(), actions, now);
    }

    public PageResponse<PurchaseResponses.View> search(PurchaseStatus status, String from, String to, int page, int size) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.VIEW);
        if (page < 0 || size < 1 || size > 100) throw invalid();
        Instant fromTime = parse(from), toTime = parse(to);
        if (fromTime != null && toTime != null && (!fromTime.isBefore(toTime)
            || Duration.between(fromTime, toTime).compareTo(Duration.ofDays(366)) > 0)) throw invalid();
        Instant now = now(); Facts facts = facts(actor, now);
        var result = purchases.search(actor.restaurantId(), status == null ? null : status.name(), fromTime, toTime, PageRequest.of(page, size));
        return PageResponse.from(result.map(p -> mapper.view(p, actor, facts.canPurchase(), facts.effective(), now)));
    }

    public PurchaseResponses.View detail(UUID id) {
        CurrentUser actor = access.require(PurchaseAccessPolicy.VIEW);
        var purchase = purchases.findByIdAndRestaurantId(id, actor.restaurantId())
            .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_NOT_FOUND", "Purchase not found"));
        Instant now = now(); Facts facts = facts(actor, now);
        return mapper.view(purchase, actor, facts.canPurchase(), facts.effective(), now);
    }

    private Facts facts(CurrentUser actor, Instant now) {
        var sub = subscriptions.findForOnboarding(actor.restaurantId(), now).orElse(null);
        var open = purchases.findOpen(actor.restaurantId()).orElse(null);
        String reason = eligibility.blockedReason(settings.isAcceptNew(), actor, sub, open, now);
        return new Facts(sub, open, reason, sub != null && validity.isEffective(sub.getStatus(), sub.getStartAt(), sub.getEndAt(), now));
    }
    private record Facts(RestaurantSubscription subscription, SubscriptionPurchase open, String blockedReason, boolean effective) {
        boolean canPurchase() { return blockedReason == null; }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Instant parse(String value) {
        if (value == null) return null;
        try { if (!value.endsWith("Z")) throw invalid(); return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException bad) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid purchase filters"); }
}
