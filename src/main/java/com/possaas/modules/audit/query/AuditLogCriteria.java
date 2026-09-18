package com.possaas.modules.audit.query;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public record AuditLogCriteria(
    AuditScope scope,
    UUID restaurantId,
    UUID actorUserId,
    String actionCode,
    String entityType,
    UUID entityId,
    Instant from,
    Instant to
) {
    public AuditLogCriteria {
        scope = scope == null ? AuditScope.ALL : scope;
        actionCode = normalizeUppercase(actionCode);
        entityType = normalize(entityType);
    }

    private static String normalizeUppercase(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
