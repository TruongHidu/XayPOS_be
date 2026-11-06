package com.possaas.modules.subscriptionpurchase.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transfer instructions to the platform account; contains no credential and is frozen on the attempt. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PaymentInstructions(String bankCode, String accountNumber, String accountName,
    String transferContent, String qrPayload, String qrImageUrl) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bankCode", bankCode); m.put("accountNumber", accountNumber); m.put("accountName", accountName);
        m.put("transferContent", transferContent); m.put("qrPayload", qrPayload); m.put("qrImageUrl", qrImageUrl);
        return Collections.unmodifiableMap(m);
    }

    public static PaymentInstructions fromMap(Map<?, ?> m) {
        return new PaymentInstructions(text(m.get("bankCode")), text(m.get("accountNumber")), text(m.get("accountName")),
            text(m.get("transferContent")), text(m.get("qrPayload")), text(m.get("qrImageUrl")));
    }
    private static String text(Object v) { return v == null ? null : v.toString(); }
}
