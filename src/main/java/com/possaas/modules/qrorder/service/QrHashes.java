package com.possaas.modules.qrorder.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class QrHashes {
    private final ObjectMapper canonical;
    public QrHashes(ObjectMapper mapper) {
        canonical=mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
        // Fingerprints own their time representation; legacy staff's mapper/bytes remain untouched.
        canonical.registerModule(new SimpleModule("qr-canonical-time").addSerializer(Instant.class,new JsonSerializer<Instant>() {
            @Override public void serialize(Instant instant,JsonGenerator generator,SerializerProvider provider) throws IOException {
                generator.writeString(instant.toString());
            }
        }));
    }
    public String hash(Object canonicalIntent) {
        try { return digest(canonical.writeValueAsString(canonicalIntent)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Cannot fingerprint QR intent"); }
    }
    public static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
