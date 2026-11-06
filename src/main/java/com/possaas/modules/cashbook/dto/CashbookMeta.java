package com.possaas.modules.cashbook.dto;
import java.time.Instant;
import java.time.LocalDate;
public record CashbookMeta(LocalDate fromDate, LocalDate toDate, String timezone, String currencyCode,
    String dateBasis, String granularity, Instant generatedAt) {}
