package com.possaas.modules.report.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.LocalDate;

public record ReferenceMarginSeriesPoint(LocalDate bucketStartDate, @JsonUnwrapped ReferenceMarginSummary metrics) {}
