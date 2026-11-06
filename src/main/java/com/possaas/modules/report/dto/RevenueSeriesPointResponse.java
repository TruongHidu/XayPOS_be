package com.possaas.modules.report.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RevenueSeriesPointResponse(
    LocalDate bucketStartDate,
    BigDecimal collectedAmount,
    BigDecimal completedOrderSalesAmount,
    long successfulPaymentCount,
    long completedOrderCount
) {}
