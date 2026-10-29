package com.possaas.modules.report.dto;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReferenceMarginOrderRow(
    UUID orderId, String orderCode, Instant completedAt, ServiceType serviceType, SourceChannel sourceChannel,
    BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal serviceChargeAmount,
    BigDecimal salesAmount, BigDecimal netSalesAmount, long eligibleItemCount, long fullyCostedItemCount,
    String costCoverageStatus, List<ReferenceMarginIssue> costIssues,
    BigDecimal referenceCostAmount, BigDecimal referenceMarginAmount, BigDecimal referenceMarginPercent
) {}
