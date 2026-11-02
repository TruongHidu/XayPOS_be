package com.possaas.modules.subscriptionpurchase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot.FeatureGrant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PurchaseQuote(
    int schemaVersion, UUID packageId, String packageCode, String packageName,
    long priceAmount, String currencyCode, int billingCycleMonths,
    List<FeatureGrant> features, Long maxStaff, Instant quotedAt, Instant expiresAt, String billingZoneId
) {
    public PurchaseQuote { features = List.copyOf(features); }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schemaVersion);
        result.put("packageId", packageId.toString());
        result.put("packageCode", packageCode);
        result.put("packageName", packageName);
        result.put("priceAmount", priceAmount);
        result.put("currencyCode", currencyCode);
        result.put("billingCycleMonths", billingCycleMonths);
        result.put("features", features.stream().map(f -> Map.of("code", f.code(), "limits", f.limits())).toList());
        result.put("maxStaff", maxStaff);
        result.put("quotedAt", quotedAt.toString());
        result.put("expiresAt", expiresAt.toString());
        result.put("billingZoneId", billingZoneId);
        return Collections.unmodifiableMap(result);
    }

    public static PurchaseQuote fromMap(Map<String, Object> source) {
        List<FeatureGrant> grants = new ArrayList<>();
        for (Object value : (List<?>) source.get("features")) {
            Map<?, ?> grant = (Map<?, ?>) value;
            Map<String, Object> limits = new LinkedHashMap<>();
            ((Map<?, ?>) grant.get("limits")).forEach((k, v) -> limits.put(k.toString(), v));
            grants.add(new FeatureGrant(grant.get("code").toString(), limits));
        }
        return new PurchaseQuote(((Number) source.get("schemaVersion")).intValue(),
            UUID.fromString(source.get("packageId").toString()), source.get("packageCode").toString(),
            source.get("packageName").toString(), ((Number) source.get("priceAmount")).longValue(),
            source.get("currencyCode").toString(), ((Number) source.get("billingCycleMonths")).intValue(),
            grants, source.get("maxStaff") == null ? null : ((Number) source.get("maxStaff")).longValue(),
            Instant.parse(source.get("quotedAt").toString()), Instant.parse(source.get("expiresAt").toString()),
            source.get("billingZoneId").toString());
    }
}
