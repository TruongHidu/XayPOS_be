package com.possaas.modules.report.service;

import com.possaas.modules.report.dto.*;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository.Aggregate;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository.OrderRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReferenceMarginPolicy {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public ReferenceMarginSummary summarize(Aggregate a) {
        long total = a.completedOrderCount();
        long complete = a.fullyCostedOrderCount();
        BigDecimal net = money(a.subtotalAmount()).subtract(money(a.discountAmount()));
        BigDecimal covered = money(a.coveredNetSalesAmount());
        BigDecimal cost = money(a.coveredReferenceCostAmount());
        BigDecimal margin = covered.subtract(cost);
        return new ReferenceMarginSummary("CATALOG_REFERENCE_AT_ACCEPTANCE", "FULLY_COSTED_COMPLETED_ORDERS",
            status(total, complete), total, complete, total - complete,
            total == 0 ? null : percent(BigDecimal.valueOf(complete), BigDecimal.valueOf(total)),
            money(a.subtotalAmount()), money(a.discountAmount()), money(a.taxAmount()), money(a.serviceChargeAmount()),
            money(a.salesAmount()), net, covered, cost, margin, percent(margin, covered),
            issues(a.incompleteOrderCount(), a.incompleteItemCount(), a.unavailableOrderCount(), a.unavailableItemCount(),
                a.legacyOrderCount(), a.legacyItemCount(), a.noEligibleOrderCount()));
    }

    public ReferenceMarginSummary empty() {
        return summarize(new Aggregate(0, 0, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, 0, 0, 0, 0, 0, 0, 0));
    }

    public ReferenceMarginOrderRow order(OrderRow row) {
        BigDecimal net = money(row.subtotalAmount()).subtract(money(row.discountAmount()));
        BigDecimal cost = row.referenceCostAmount() == null ? null : money(row.referenceCostAmount());
        BigDecimal margin = cost == null ? null : net.subtract(cost);
        String coverage = cost != null ? "COMPLETE" : row.fullyCostedItemCount() > 0 ? "PARTIAL" : "UNAVAILABLE";
        return new ReferenceMarginOrderRow(row.orderId(), row.orderCode(), row.completedAt(), row.serviceType(), row.sourceChannel(),
            money(row.subtotalAmount()), money(row.discountAmount()), money(row.taxAmount()), money(row.serviceChargeAmount()),
            money(row.salesAmount()), net, row.eligibleItemCount(), row.fullyCostedItemCount(), coverage,
            issues(row.incompleteItemCount() > 0 ? 1 : 0, row.incompleteItemCount(),
                row.unavailableItemCount() > 0 ? 1 : 0, row.unavailableItemCount(),
                row.legacyItemCount() > 0 ? 1 : 0, row.legacyItemCount(), row.eligibleItemCount() == 0 ? 1 : 0),
            cost, margin, margin == null ? null : percent(margin, net));
    }

    private static String status(long total, long complete) {
        if (total == 0) return "NO_DATA";
        if (complete == total) return "COMPLETE";
        return complete == 0 ? "UNAVAILABLE" : "PARTIAL";
    }

    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.UNNECESSARY); }

    private static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() <= 0 ? null : numerator.multiply(HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static List<ReferenceMarginIssue> issues(long incompleteOrders, long incompleteItems,
            long unavailableOrders, long unavailableItems, long legacyOrders, long legacyItems, long noEligibleOrders) {
        var result = new ArrayList<ReferenceMarginIssue>();
        if (incompleteOrders > 0) result.add(new ReferenceMarginIssue("INCOMPLETE", incompleteOrders, incompleteItems));
        if (unavailableOrders > 0) result.add(new ReferenceMarginIssue("UNAVAILABLE", unavailableOrders, unavailableItems));
        if (legacyOrders > 0) result.add(new ReferenceMarginIssue("LEGACY_UNAVAILABLE", legacyOrders, legacyItems));
        if (noEligibleOrders > 0) result.add(new ReferenceMarginIssue("NO_ELIGIBLE_ITEMS", noEligibleOrders, 0));
        return List.copyOf(result);
    }
}
