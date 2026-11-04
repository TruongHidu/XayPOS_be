package com.possaas.infrastructure.subscriptionpayment.sepay;

import static org.assertj.core.api.Assertions.*;

import com.possaas.infrastructure.subscriptionpayment.sepay.SepayWebhookVerifier.Result;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SepayWebhookVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final String SECRET = "test-webhook-secret";
    private static final byte[] BODY = "{\"id\":1,\"transferAmount\":399000}".getBytes(StandardCharsets.UTF_8);
    private final SepayWebhookVerifier verifier = verifier(SECRET);

    @Test void acceptsSignatureOverTimestampDotRawBody() {
        String ts = Long.toString(NOW.getEpochSecond());
        assertThat(verifier.verify(ts, sign(ts, BODY), BODY)).isEqualTo(Result.VALID);
        assertThat(verifier.verify(ts, sign(ts, BODY).toUpperCase().replace("SHA256=", "sha256="), BODY)).isEqualTo(Result.VALID);
    }

    @Test void rejectsTamperedBodyWrongSecretAndMalformedSignature() {
        String ts = Long.toString(NOW.getEpochSecond());
        byte[] tampered = "{\"id\":1,\"transferAmount\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(ts, sign(ts, BODY), tampered)).isEqualTo(Result.SIGNATURE_INVALID);
        assertThat(verifier("other-secret").verify(ts, sign(ts, BODY), BODY)).isEqualTo(Result.SIGNATURE_INVALID);
        assertThat(verifier.verify(ts, sign(ts, BODY).substring("sha256=".length()), BODY)).isEqualTo(Result.SIGNATURE_INVALID);
        assertThat(verifier.verify(ts, null, BODY)).isEqualTo(Result.SIGNATURE_INVALID);
        assertThat(verifier("").verify(ts, sign(ts, BODY), BODY)).isEqualTo(Result.SIGNATURE_INVALID);
    }

    @Test void rejectsTimestampOutsideFiveMinutesOrNotNumeric() {
        for (long offset : new long[] {301, -301}) {
            String ts = Long.toString(NOW.getEpochSecond() + offset);
            assertThat(verifier.verify(ts, sign(ts, BODY), BODY)).isEqualTo(Result.TIMESTAMP_INVALID);
        }
        String edge = Long.toString(NOW.getEpochSecond() - 300);
        assertThat(verifier.verify(edge, sign(edge, BODY), BODY)).isEqualTo(Result.VALID);
        assertThat(verifier.verify("2026-11-02", "sha256=00", BODY)).isEqualTo(Result.TIMESTAMP_INVALID);
        assertThat(verifier.verify(null, "sha256=00", BODY)).isEqualTo(Result.TIMESTAMP_INVALID);
    }

    static String sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static SepayWebhookVerifier verifier(String secret) {
        SepayWebhookProperties properties = new SepayWebhookProperties();
        properties.setSecret(secret);
        return new SepayWebhookVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
