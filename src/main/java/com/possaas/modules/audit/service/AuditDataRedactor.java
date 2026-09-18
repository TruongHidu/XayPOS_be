package com.possaas.modules.audit.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Creates a recursively redacted copy of JSON-like audit data.
 *
 * <p>The source object is never mutated. Key matching is case-insensitive and also treats camelCase,
 * snake_case, and kebab-case spellings as equivalent.</p>
 */
@Component
public class AuditDataRedactor {
    static final String REDACTED_VALUE = "[REDACTED]";

    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password",
        "passwordhash",
        "token",
        "accesstoken",
        "refreshtoken",
        "tokenhash",
        "authorization",
        "secret",
        "publicordertoken"
    );

    public Map<String, Object> redact(Map<String, Object> source) {
        if (source == null) {
            return null;
        }

        Map<String, Object> redacted = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> redacted.put(
            key,
            isSensitive(key) ? REDACTED_VALUE : redactValue(value)
        ));
        return redacted;
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>(map.size());
            map.forEach((key, nestedValue) -> {
                String stringKey = String.valueOf(key);
                nested.put(
                    stringKey,
                    isSensitive(stringKey) ? REDACTED_VALUE : redactValue(nestedValue)
                );
            });
            return nested;
        }

        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(redactValue(item)));
            return nested;
        }

        return value;
    }

    private boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key
            .replace("_", "")
            .replace("-", "")
            .toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(normalized);
    }
}
