package com.possaas.modules.report.dto;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import java.time.Instant;
import java.time.LocalDate;

public record ReportMeta(
    LocalDate fromDate,
    LocalDate toDate,
    String timezone,
    String currencyCode,
    Instant generatedAt,
    ServiceType serviceType,
    SourceChannel sourceChannel,
    ReportGranularity granularity,
    ReportDateBasis dateBasis
) {}
