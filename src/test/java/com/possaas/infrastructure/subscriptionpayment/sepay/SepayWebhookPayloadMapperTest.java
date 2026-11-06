package com.possaas.infrastructure.subscriptionpayment.sepay;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SepayWebhookPayloadMapperTest {
    private final SepayWebhookPayloadMapper mapper = new SepayWebhookPayloadMapper(new ObjectMapper(), account());

    @Test void normalizesVietnamTimeBankIdentityAndAllowlist() {
        var event = mapper.map(body("""
            {"id":92704,"gateway":"MBBank","transactionDate":"2026-11-02 10:05:00","accountNumber":"0123456789",
             "subAccount":"","code":"PS0123456789ABCDEF0123","content":"PS0123456789ABCDEF0123 chuyen tien",
             "transferType":"in","description":"x","transferAmount":399000,"accumulated":5000000,"referenceCode":"FT001",
             "newProviderField":"ignored"}"""));
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-02T03:05:00Z"));
        assertThat(event.providerTransactionId()).isEqualTo("BANK:FT001");
        assertThat(event.providerWebhookId()).isEqualTo(92704L);
        assertThat(event.providerAccountKey()).isEqualTo("platform-demo");
        assertThat(event.amount()).isEqualTo(399000);
        assertThat(event.receiverSubAccount()).isNull();
        assertThat(event.payload()).containsKeys("id", "code", "content").doesNotContainKeys("accumulated", "newProviderField");
        assertThat(event.payloadHash()).hasSize(64);
    }

    @Test void missingBankReferenceFallsBackToWebhookIdAndNullCodeIsAllowed() {
        var event = mapper.map(body("""
            {"id":7,"gateway":"MBBank","transactionDate":"2026-11-02 10:05:00","accountNumber":"0123456789",
             "subAccount":null,"code":null,"content":"abc","transferType":"out","description":"","transferAmount":1,
             "accumulated":0,"referenceCode":""}"""));
        assertThat(event.providerTransactionId()).isEqualTo("WEBHOOK:7");
        assertThat(event.bankReference()).isNull();
        assertThat(event.code()).isNull();
        assertThat(event.direction()).isEqualTo("out");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "{\"id\":1}",
        "{\"id\":1,\"gateway\":\"MB\",\"transactionDate\":\"2026-11-02 10:05:00\",\"accountNumber\":\"1\",\"transferType\":\"in\",\"transferAmount\":0}",
        "{\"id\":1,\"gateway\":\"MB\",\"transactionDate\":\"2026-11-02 10:05:00\",\"accountNumber\":\"1\",\"transferType\":\"in\",\"transferAmount\":1.5}",
        "{\"id\":1,\"gateway\":\"MB\",\"transactionDate\":\"02/11/2026\",\"accountNumber\":\"1\",\"transferType\":\"in\",\"transferAmount\":1}",
        "{\"id\":1,\"gateway\":\"MB\",\"transactionDate\":\"2026-11-02 10:05:00\",\"accountNumber\":\"1\",\"transferType\":\"x\",\"transferAmount\":1}"})
    void rejectsInvalidPayloads(String json) {
        assertThatThrownBy(() -> mapper.map(body(json))).isInstanceOf(SepayWebhookPayloadMapper.InvalidPayloadException.class);
    }

    private static byte[] body(String json) { return json.getBytes(StandardCharsets.UTF_8); }
    private static SepayProperties account() {
        SepayProperties properties = new SepayProperties();
        properties.setProviderAccountKey("platform-demo");
        return properties;
    }
}
