package com.possaas.modules.report.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TopItemReportResponse(
    UUID itemId,
    String itemName,
    String unit,
    BigDecimal quantity,
    BigDecimal itemSalesAmount,
    long completedOrderCount
) {}
