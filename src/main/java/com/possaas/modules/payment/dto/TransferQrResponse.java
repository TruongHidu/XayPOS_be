package com.possaas.modules.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the customer should transfer for an order, as text and as a VietQR payload. Display-only: showing it
 * records nothing, and staff still confirm the payment once the money arrives.
 */
public record TransferQrResponse(UUID orderId, long orderVersion, BigDecimal amount, String currencyCode,
    String bankBin, String accountNumber, String accountName, String transferContent, String qrPayload) {
}
