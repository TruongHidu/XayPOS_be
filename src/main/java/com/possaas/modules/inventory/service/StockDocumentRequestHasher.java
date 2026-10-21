package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.dto.StockDocumentRequests;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class StockDocumentRequestHasher {
    public String hash(StockDocumentRequests.Create request) {
        StringBuilder normalized = new StringBuilder()
                .append(request.documentType()).append('|')
                .append(request.warehouseId()).append('|')
                .append(text(request.note())).append('|');
        request.safeItems().stream()
                .sorted(Comparator.comparing(line -> line.ingredientId().toString()))
                .forEach(line -> normalized.append(line.ingredientId()).append(':')
                        .append(number(line.quantity())).append(':')
                        .append(number(line.unitCost())).append(':')
                        .append(line.adjustmentDirection()).append(':')
                        .append(text(line.note())).append('|'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String number(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
