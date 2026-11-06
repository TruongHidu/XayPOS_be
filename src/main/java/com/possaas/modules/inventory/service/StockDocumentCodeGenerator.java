package com.possaas.modules.inventory.service;

import com.possaas.modules.inventory.entity.StockDocumentType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockDocumentCodeGenerator {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private final Clock clock;

    public String generate(StockDocumentType type) {
        String prefix = switch (type) {
            case RECEIPT -> "RCV";
            case ISSUE -> "ISS";
            case ADJUSTMENT -> "ADJ";
        };
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        return prefix + "-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)).format(DATE) + "-" + suffix;
    }
}
