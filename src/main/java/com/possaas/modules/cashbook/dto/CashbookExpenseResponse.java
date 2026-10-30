package com.possaas.modules.cashbook.dto;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record CashbookExpenseResponse(UUID id,String expenseCode,String categoryCode,String categoryName,
    BigDecimal amount,String currencyCode,PaymentMethod paymentMethod,Instant expenseAt,UUID stockDocumentId,
    String sourceDocumentCode,String sourceCurrencyStatus,String referenceNo,String payeeName,String note,
    String status,UUID createdBy,Instant createdAt,Instant updatedAt,long version,
    UUID voidedBy,Instant voidedAt,String voidReason) {}
