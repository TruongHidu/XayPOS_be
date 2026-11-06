package com.possaas.infrastructure.subscriptionpayment.sepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.subscriptionpurchase.application.port.VerifiedPaymentEvent;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Normalizes a verified SePay webhook body; unknown provider fields are tolerated and not stored. */
@Component
@RequiredArgsConstructor
public class SepayWebhookPayloadMapper {
    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter BANK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> STORED_FIELDS = List.of("id", "gateway", "transactionDate", "accountNumber", "subAccount",
        "code", "content", "transferType", "description", "transferAmount", "referenceCode");
    private final ObjectMapper json;
    private final SepayProperties account;

    public static class InvalidPayloadException extends RuntimeException {
        InvalidPayloadException(String message) { super(message); }
    }

    public VerifiedPaymentEvent map(byte[] rawBody) {
        JsonNode root;
        try { root = json.readTree(rawBody); }
        catch (IOException invalid) { throw new InvalidPayloadException("Body is not JSON"); }
        if (root == null || !root.isObject()) throw new InvalidPayloadException("Body is not a JSON object");
        JsonNode id = root.get("id"), amount = root.get("transferAmount");
        if (id == null || !id.canConvertToLong() || !id.isIntegralNumber() || id.asLong() <= 0) throw new InvalidPayloadException("Invalid id");
        if (amount == null || !amount.isIntegralNumber() || amount.asLong() <= 0 || amount.asLong() > 999_999_999_999L)
            throw new InvalidPayloadException("Invalid transferAmount");
        String direction = text(root, "transferType");
        if (!"in".equals(direction) && !"out".equals(direction)) throw new InvalidPayloadException("Invalid transferType");
        String receiver = required(root, "accountNumber"), bank = required(root, "gateway");
        String bankReference = trimToNull(text(root, "referenceCode"));
        if (bankReference != null && bankReference.length() > 150) throw new InvalidPayloadException("Invalid referenceCode");
        long webhookId = id.asLong();
        return new VerifiedPaymentEvent(SepayPaymentInstructionProvider.PROVIDER, account.getProviderAccountKey(),
            bankReference != null ? "BANK:" + bankReference : "WEBHOOK:" + webhookId, webhookId, bankReference,
            VerifiedPaymentEvent.SOURCE_WEBHOOK, sha256(rawBody), allowlist(root), direction, amount.asLong(), "VND",
            limit(receiver, 100), trimToNull(text(root, "subAccount")), limit(bank, 100),
            trimToNull(text(root, "code")), text(root, "content"), occurredAt(required(root, "transactionDate")));
    }

    private static java.time.Instant occurredAt(String value) {
        try { return LocalDateTime.parse(value, BANK_TIME).atZone(BANK_ZONE).toInstant(); }
        catch (DateTimeParseException invalid) { throw new InvalidPayloadException("Invalid transactionDate"); }
    }

    private static Map<String, Object> allowlist(JsonNode root) {
        Map<String, Object> stored = new LinkedHashMap<>();
        for (String field : STORED_FIELDS) {
            JsonNode value = root.get(field);
            if (value == null || value.isNull()) stored.put(field, null);
            else if (value.isIntegralNumber()) stored.put(field, value.asLong());
            else stored.put(field, value.asText());
        }
        return Collections.unmodifiableMap(stored);
    }

    private static String required(JsonNode root, String field) {
        String value = trimToNull(text(root, field));
        if (value == null) throw new InvalidPayloadException("Missing " + field);
        return value;
    }
    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String limit(String value, int max) {
        if (value.length() > max) throw new InvalidPayloadException("Field too long");
        return value;
    }
    private static String sha256(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
