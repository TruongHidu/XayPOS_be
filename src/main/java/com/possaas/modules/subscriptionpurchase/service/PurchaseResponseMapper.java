package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseQuote;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses.View;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PurchaseResponseMapper {
    public List<String> actions(PurchaseStatus status, CurrentUser actor, boolean canPurchase, boolean effective) {
        List<String> actions = new ArrayList<>();
        if (status == PurchaseStatus.PENDING_PAYMENT) {
            actions.add("VIEW_PURCHASE");
            if (actor.hasPermission(PurchaseAccessPolicy.CANCEL)) actions.add("CANCEL_PURCHASE");
        } else if (status == PurchaseStatus.PAID) actions.add("WAIT_ACTIVATION");
        else if (status == PurchaseStatus.REVIEW_REQUIRED || status == PurchaseStatus.CLOSED) actions.add("CONTACT_SUPPORT");
        else if (status == PurchaseStatus.ACTIVATED && effective) actions.add("OPEN_APP");
        else if (canPurchase) actions.add("CREATE_PURCHASE");
        return List.copyOf(actions);
    }

    public View view(SubscriptionPurchase p, CurrentUser actor, boolean canPurchase, boolean effective, Instant now) {
        PurchaseStatus display = p.isDue(now) ? PurchaseStatus.EXPIRED : p.getStatus();
        return new View(p.getId(), p.getStatus(), display, PurchaseQuote.fromMap(p.getQuote()), p.getSubscriptionId(),
            p.getPaidAt(), p.getActivatedAt(), p.getCancelledAt(), p.getClosedAt(), p.getReviewReason(),
            actions(display, actor, canPurchase, effective), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion(), now);
    }

    public View initial(UUID id, PurchaseQuote quote, CurrentUser actor) {
        return new View(id, PurchaseStatus.PENDING_PAYMENT, PurchaseStatus.PENDING_PAYMENT, quote,
            null, null, null, null, null, null, actions(PurchaseStatus.PENDING_PAYMENT, actor, false, false),
            quote.quotedAt(), quote.quotedAt(), 0, quote.quotedAt());
    }

    /** Explicit JSON value model keeps historical replay independent of MVC Jackson major version. */
    public Map<String, Object> freeze(View v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id().toString()); m.put("status", v.status().name()); m.put("displayStatus", v.displayStatus().name());
        m.put("quote", v.quote().toMap()); m.put("subscriptionId", string(v.subscriptionId()));
        m.put("paidAt", string(v.paidAt())); m.put("activatedAt", string(v.activatedAt()));
        m.put("cancelledAt", string(v.cancelledAt())); m.put("closedAt", string(v.closedAt()));
        m.put("reviewReason", v.reviewReason()); m.put("allowedActions", v.allowedActions());
        m.put("createdAt", v.createdAt().toString()); m.put("updatedAt", v.updatedAt().toString());
        m.put("version", v.version()); m.put("serverTime", v.serverTime().toString());
        return Collections.unmodifiableMap(m);
    }

    public View replay(Map<String, Object> m) {
        Map<String, Object> quote = new LinkedHashMap<>();
        ((Map<?, ?>) m.get("quote")).forEach((k, v) -> quote.put(k.toString(), v));
        return new View(UUID.fromString(m.get("id").toString()), PurchaseStatus.valueOf(m.get("status").toString()),
            PurchaseStatus.valueOf(m.get("displayStatus").toString()), PurchaseQuote.fromMap(quote),
            m.get("subscriptionId") == null ? null : UUID.fromString(m.get("subscriptionId").toString()),
            instant(m.get("paidAt")), instant(m.get("activatedAt")), instant(m.get("cancelledAt")), instant(m.get("closedAt")),
            (String) m.get("reviewReason"), ((List<?>) m.get("allowedActions")).stream().map(Object::toString).toList(),
            instant(m.get("createdAt")), instant(m.get("updatedAt")), ((Number) m.get("version")).longValue(), instant(m.get("serverTime")));
    }
    private static String string(Object v) { return v == null ? null : v.toString(); }
    private static Instant instant(Object v) { return v == null ? null : Instant.parse(v.toString()); }
}
