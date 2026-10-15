package com.possaas.modules.report.service;

import com.possaas.modules.report.dto.ReportGranularity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReportBucketPolicy {
    public List<LocalDate> buckets(ReportQueryContext context, ReportGranularity granularity) {
        var result = new ArrayList<LocalDate>();
        LocalDate cursor = granularity == ReportGranularity.MONTH
            ? context.fromDate().withDayOfMonth(1)
            : context.fromDate();
        while (!cursor.isAfter(context.toDate())) {
            result.add(cursor);
            cursor = granularity == ReportGranularity.MONTH
                ? cursor.plusMonths(1)
                : cursor.plusDays(1);
        }
        return List.copyOf(result);
    }
}
