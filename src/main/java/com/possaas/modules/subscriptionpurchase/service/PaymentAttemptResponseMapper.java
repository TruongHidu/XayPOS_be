package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.modules.subscriptionpurchase.application.port.PreparedPaymentInstructions;
import com.possaas.modules.subscriptionpurchase.dto.PaymentAttemptResponses.View;
import com.possaas.modules.subscriptionpurchase.dto.PaymentInstructions;
import com.possaas.modules.subscriptionpurchase.entity.AttemptStatus;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPaymentAttempt;
import com.possaas.modules.subscriptionpurchase.entity.SubscriptionPurchase;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaymentAttemptResponseMapper {
    public View view(SubscriptionPaymentAttempt a, Instant now) {
        return new View(a.getId(), a.getPurchaseId(), a.getStatus(), a.displayStatus(now), a.getProvider(), a.getReferenceCode(),
            a.getAmount().longValueExact(), a.getCurrencyCode(), a.getExpiresAt(), PaymentInstructions.fromMap(a.getInstructions()),
            a.getPaidAt(), a.getCreatedAt(), a.getUpdatedAt(), a.getVersion(), now);
    }

    public View initial(UUID id, SubscriptionPurchase purchase, String reference, PreparedPaymentInstructions prepared, Instant now) {
        return new View(id, purchase.getId(), AttemptStatus.PENDING, AttemptStatus.PENDING, prepared.provider(), reference,
            purchase.getAmount().longValueExact(), purchase.getCurrencyCode(), purchase.getExpiresAt(), prepared.instructions(),
            null, now, now, 0, now);
    }

    /** Explicit JSON value model keeps historical replay independent of MVC Jackson major version. */
    public Map<String, Object> freeze(View v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id().toString()); m.put("purchaseId", v.purchaseId().toString());
        m.put("status", v.status().name()); m.put("displayStatus", v.displayStatus().name());
        m.put("provider", v.provider()); m.put("referenceCode", v.referenceCode());
        m.put("amount", v.amount()); m.put("currencyCode", v.currencyCode()); m.put("expiresAt", v.expiresAt().toString());
        m.put("instructions", v.instructions().toMap()); m.put("paidAt", v.paidAt() == null ? null : v.paidAt().toString());
        m.put("createdAt", v.createdAt().toString()); m.put("updatedAt", v.updatedAt().toString());
        m.put("version", v.version()); m.put("serverTime", v.serverTime().toString());
        return Collections.unmodifiableMap(m);
    }

    public View replay(Map<String, Object> m) {
        return new View(UUID.fromString(m.get("id").toString()), UUID.fromString(m.get("purchaseId").toString()),
            AttemptStatus.valueOf(m.get("status").toString()), AttemptStatus.valueOf(m.get("displayStatus").toString()),
            m.get("provider").toString(), m.get("referenceCode").toString(), ((Number) m.get("amount")).longValue(),
            m.get("currencyCode").toString(), Instant.parse(m.get("expiresAt").toString()),
            PaymentInstructions.fromMap((Map<?, ?>) m.get("instructions")),
            m.get("paidAt") == null ? null : Instant.parse(m.get("paidAt").toString()),
            Instant.parse(m.get("createdAt").toString()), Instant.parse(m.get("updatedAt").toString()),
            ((Number) m.get("version")).longValue(), Instant.parse(m.get("serverTime").toString()));
    }
}
