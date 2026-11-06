package com.possaas.infrastructure.subscriptionpayment.sepay;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** SePay HMAC: X-SePay-Signature = "sha256=" + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)). */
@Component
@RequiredArgsConstructor
public class SepayWebhookVerifier {
    public enum Result { VALID, TIMESTAMP_INVALID, SIGNATURE_INVALID }
    private static final String PREFIX = "sha256=";
    private final SepayWebhookProperties properties;
    private final Clock clock;

    public Result verify(String timestamp, String signature, byte[] rawBody) {
        if (timestamp == null || !timestamp.matches("\\d{10,12}")) return Result.TIMESTAMP_INVALID;
        Duration skew = Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp)), clock.instant()).abs();
        if (skew.compareTo(properties.getTimestampTolerance()) > 0) return Result.TIMESTAMP_INVALID;
        if (!StringUtils.hasText(properties.getSecret()) || signature == null || !signature.startsWith(PREFIX))
            return Result.SIGNATURE_INVALID;
        byte[] expected = (PREFIX + HexFormat.of().formatHex(hmac(timestamp, rawBody))).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual) ? Result.VALID : Result.SIGNATURE_INVALID;
    }

    private byte[] hmac(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(rawBody);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
