package com.possaas.modules.order.application.port;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only view of an order that can be paid now, for payment features that only show what to collect
 * (such as a transfer QR). The order module keeps the rule for what "payable" means.
 */
public interface PayableOrderQuery {
    /** Throws ORDER_NOT_FOUND outside the tenant and ORDER_ALREADY_PAID / ORDER_NOT_PAYABLE otherwise. */
    PayableOrder requirePayable(UUID restaurantId, UUID orderId);

    record PayableOrder(UUID orderId, String orderCode, BigDecimal totalAmount, String currencyCode, long version) {}
}
