package com.possaas.modules.report.dto;

import java.math.BigDecimal;

public record ReportOverviewResponse(CollectionsMetrics collections, OrderMetrics orders) {
    public record CollectionsMetrics(
        BigDecimal collectedAmount,
        long successfulPaymentCount,
        long paidOrderCount
    ) {}

    public record OrderMetrics(
        long createdOrderCount,
        long completedOrderCount,
        long cancelledOrderCount,
        BigDecimal completedSubtotalAmount,
        BigDecimal completedDiscountAmount,
        BigDecimal completedTaxAmount,
        BigDecimal completedServiceChargeAmount,
        BigDecimal completedOrderSalesAmount,
        BigDecimal averageCompletedOrderValue
    ) {}
}
