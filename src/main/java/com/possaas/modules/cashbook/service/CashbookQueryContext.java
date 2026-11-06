package com.possaas.modules.cashbook.service;
import com.possaas.modules.cashbook.dto.CashbookMeta;
import java.time.*;
import java.util.UUID;
public record CashbookQueryContext(UUID restaurantId, LocalDate fromDate, LocalDate toDate, ZoneId zone,
    Instant fromInclusive, Instant toExclusive, String currencyCode, Instant generatedAt) {
    public CashbookMeta meta(String basis, String granularity) {
        return new CashbookMeta(fromDate,toDate,zone.getId(),currencyCode,basis,granularity,generatedAt);
    }
}
