package com.possaas.infrastructure.realtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** An immutable invalidation hint. Authoritative business data stays in REST. */
public record RealtimeEventEnvelope(
        UUID eventId,
        String type,
        int schemaVersion,
        String resourceType,
        UUID resourceId,
        long resourceVersion,
        Instant occurredAt,
        Map<String, Object> payload) {
    public RealtimeEventEnvelope {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(type);
        Objects.requireNonNull(resourceType);
        Objects.requireNonNull(resourceId);
        Objects.requireNonNull(occurredAt);
        // resolutionReason is nullable, so Map.copyOf is deliberately unsuitable.
        payload = freezeMap(Objects.requireNonNull(payload));
        if (schemaVersion < 1 || resourceVersion < 0) {
            throw new IllegalArgumentException("Invalid realtime event version");
        }
    }
    private static Map<String, Object> freezeMap(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(Objects.requireNonNull(key), freeze(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if (value == null || value instanceof String || value instanceof UUID || value instanceof Instant
                || value instanceof Boolean || value instanceof Enum<?> || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigDecimal) return value;
        if (value instanceof List<?> list) return list.stream().map(RealtimeEventEnvelope::freeze).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> { if (!(k instanceof String key)) throw new IllegalArgumentException("Invalid payload key");
                result.put(key, freeze(v)); });
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("Unsupported mutable realtime payload value");
    }
}
