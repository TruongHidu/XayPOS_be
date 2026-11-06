package com.possaas.modules.report.dto;

import java.math.BigDecimal;
import java.util.List;

public record ReferenceMarginSummary(
    String costBasis, String marginScope, String costCoverageStatus,
    long completedOrderCount, long fullyCostedOrderCount, long excludedOrderCount,
    BigDecimal orderCoveragePercent,
    BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal taxAmount,
    BigDecimal serviceChargeAmount, BigDecimal salesAmount, BigDecimal netSalesAmount,
    BigDecimal coveredNetSalesAmount, BigDecimal coveredReferenceCostAmount,
    BigDecimal coveredReferenceMarginAmount, BigDecimal coveredReferenceMarginPercent,
    List<ReferenceMarginIssue> costIssues
) {}
