package com.possaas.modules.subscription.domain;

import com.possaas.common.exception.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public record SubscriptionFeatureSnapshot(
    int schemaVersion,
    String packageCode,
    List<FeatureGrant> features,
    Instant capturedAt,
    Long maxStaff
) {
    public SubscriptionFeatureSnapshot(int schemaVersion, String packageCode, List<FeatureGrant> features, Instant capturedAt) {
        this(schemaVersion, packageCode, features, capturedAt, null);
    }

    /** Legacy snapshots remain stored verbatim; only their effective limit is adapted on read. */
    public Long effectiveMaxStaff() {
        if (schemaVersion >= 2) return maxStaff;
        return features.stream().filter(f -> "STAFF_MANAGEMENT".equals(f.code()))
            .filter(f -> f.limits().containsKey("maxStaff"))
            .findFirst().map(f -> readLimit(f.limits().get("maxStaff"))).orElse(null);
    }

    private static Long readLimit(Object value) {
        try {
            return FeatureLimitValues.positiveLong(value);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new BusinessException(HttpStatus.CONFLICT,
                "INVALID_STAFF_LIMIT_CONFIG", "Snapshot staff limit must be a positive integer");
        }
    }
    public SubscriptionFeatureSnapshot {
        features = features == null ? List.of() : List.copyOf(features);
    }

    public boolean contains(String featureCode) {
        return features.stream().anyMatch(feature -> feature.code().equals(featureCode));
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> featureValues = features.stream()
            .map(feature -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("code", feature.code());
                value.put("limits", feature.limits());
                return value;
            })
            .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", schemaVersion);
        snapshot.put("packageCode", packageCode);
        snapshot.put("features", featureValues);
        snapshot.put("capturedAt", capturedAt.toString());
        if (schemaVersion >= 2) snapshot.put("maxStaff", maxStaff);
        return snapshot;
    }

    public static SubscriptionFeatureSnapshot fromMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new SubscriptionFeatureSnapshot(1, null, List.of(), Instant.EPOCH);
        }
        Object versionValue = source.getOrDefault("schemaVersion", 1);
        int version = versionValue instanceof Number number ? number.intValue() : Integer.parseInt(versionValue.toString());
        String packageCode = source.get("packageCode") == null ? null : source.get("packageCode").toString();
        Instant capturedAt = source.get("capturedAt") == null
            ? Instant.EPOCH
            : Instant.parse(source.get("capturedAt").toString());
        List<FeatureGrant> grants = new ArrayList<>();
        Object rawFeatures = source.get("features");
        if (rawFeatures instanceof Iterable<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> featureValue) || featureValue.get("code") == null) {
                    continue;
                }
                Map<String, Object> limits = new LinkedHashMap<>();
                Object rawLimits = featureValue.get("limits");
                if (rawLimits instanceof Map<?, ?> limitValues) {
                    limitValues.forEach((key, limit) -> limits.put(String.valueOf(key), limit));
                }
                grants.add(new FeatureGrant(featureValue.get("code").toString(), limits));
            }
        }
        Long maxStaff = null;
        if (version >= 2) {
            if (!source.containsKey("maxStaff")) readLimit(null); // Missing is corrupt; explicit null means unlimited.
            if (source.get("maxStaff") != null) maxStaff = readLimit(source.get("maxStaff"));
        }
        return new SubscriptionFeatureSnapshot(version, packageCode, grants, capturedAt, maxStaff);
    }

    public record FeatureGrant(String code, Map<String, Object> limits) {
        public FeatureGrant {
            limits = limits == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(limits));
        }
    }
}
