package com.possaas.modules.cashbook.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public record CashbookStockReceiptResponse(UUID stockDocumentId, String documentCode, UUID warehouseId,
    String warehouseName, Instant confirmedAt, BigDecimal totalCost, String currencyCode,
    String currencyStatus, long linkedPostedExpenseCount, BigDecimal recordedExpenseAmountAllTime) {}
