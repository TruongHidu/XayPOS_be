package com.possaas.modules.report.service;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.ReportDateBasis;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.dto.ReportMeta;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public record ReportQueryContext(
    UUID restaurantId,
    LocalDate fromDate,
    LocalDate toDate,
    ZoneId zone,
    Instant fromInclusive,
    Instant toExclusive,
    String currencyCode,
    ServiceType serviceType,
    SourceChannel sourceChannel,
    Instant generatedAt
) {
    public ReportMeta meta(ReportGranularity granularity, ReportDateBasis dateBasis) {
        return new ReportMeta(
            fromDate,
            toDate,
            zone.getId(),
            currencyCode,
            generatedAt,
            serviceType,
            sourceChannel,
            granularity,
            dateBasis
        );
    }
}
