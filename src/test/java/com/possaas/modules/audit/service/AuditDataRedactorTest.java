package com.possaas.modules.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditDataRedactorTest {
    private final AuditDataRedactor redactor = new AuditDataRedactor();

    @Test
    void recursivelyRedactsSensitiveValuesWithoutMutatingInput() {
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        nested.put("access_token", "access-secret");
        nested.put("safe", "visible");
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("PasswordHash", "password-secret");
        source.put("items", List.of(nested, Map.of("public-order-token", "public-secret")));

        Map<String, Object> result = redactor.redact(source);

        assertThat(result.get("PasswordHash")).isEqualTo(AuditDataRedactor.REDACTED_VALUE);
        assertThat(result).extractingByKey("items").asList().containsExactly(
            Map.of("access_token", AuditDataRedactor.REDACTED_VALUE, "safe", "visible"),
            Map.of("public-order-token", AuditDataRedactor.REDACTED_VALUE)
        );
        assertThat(nested.get("access_token")).isEqualTo("access-secret");
        assertThat(source.get("PasswordHash")).isEqualTo("password-secret");
    }

    @Test
    void preservesNullDocuments() {
        assertThat(redactor.redact(null)).isNull();
    }
}
