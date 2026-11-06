package com.possaas.modules.cashbook.dto;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record CashbookEntryResponse(String sourceType, UUID sourceId, String code, String type,
    String categoryCode, String categoryName, BigDecimal amount, String currencyCode,
    PaymentMethod paymentMethod, Instant occurredAt, Instant recordedAt, UUID createdBy,
    UUID orderId, String orderCode, UUID stockDocumentId, String sourceDocumentCode) {}
