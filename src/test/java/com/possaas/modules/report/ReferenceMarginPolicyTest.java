package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import com.possaas.modules.report.service.ReferenceMarginPolicy;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository.Aggregate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReferenceMarginPolicyTest {
    private final ReferenceMarginPolicy policy = new ReferenceMarginPolicy();
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @Test void marginUsesFullyCostedCohortAndPreservesIssueOverlap() {
        var a = new Aggregate(3, 1, n("500.00"), n("20.00"), n("30.00"), n("5.00"), n("515.00"),
            n("100.00"), n("120.00"), 1, 2, 1, 1, 1, 1, 0);
        var result = policy.summarize(a);
        assertThat(result.costCoverageStatus()).isEqualTo("PARTIAL");
        assertThat(result.excludedOrderCount()).isEqualTo(2);
        assertThat(result.orderCoveragePercent()).isEqualByComparingTo("33.33");
        assertThat(result.netSalesAmount()).isEqualByComparingTo("480.00");
        assertThat(result.coveredReferenceMarginAmount()).isEqualByComparingTo("-20.00");
        assertThat(result.coveredReferenceMarginPercent()).isEqualByComparingTo("-20.00");
        assertThat(result.costIssues()).hasSize(3);
    }

    @Test void emptyAndUnknownCohortNeverProduceFakePercentages() {
        var empty = policy.empty();
        assertThat(empty.costCoverageStatus()).isEqualTo("NO_DATA");
        assertThat(empty.orderCoveragePercent()).isNull();
        assertThat(empty.coveredReferenceMarginPercent()).isNull();
        var missing = policy.summarize(new Aggregate(1, 0, n("10.00"), n("0.00"), n("0.00"), n("0.00"),
            n("10.00"), n("0.00"), n("0.00"), 1, 1, 0, 0, 0, 0, 0));
        assertThat(missing.costCoverageStatus()).isEqualTo("UNAVAILABLE");
        assertThat(missing.coveredReferenceMarginPercent()).isNull();
        assertThat(missing.costIssues().getFirst().code()).isEqualTo("INCOMPLETE");
    }
}
