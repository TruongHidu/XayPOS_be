package com.possaas.modules.report.controller;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.*;
import com.possaas.modules.report.service.ReferenceMarginReportQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/reports/reference-margin")
@RequiredArgsConstructor
public class ReferenceMarginReportController {
    private final ReferenceMarginReportQueryService service;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<ReferenceMarginSummary>> summary(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel) {
        return noStore(service.summary(fromDate, toDate, currencyCode, serviceType, sourceChannel));
    }

    @GetMapping("/series")
    @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<List<ReferenceMarginSeriesPoint>>> series(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel,
        @RequestParam(defaultValue = "DAY") ReportGranularity granularity) {
        return noStore(service.series(fromDate, toDate, currencyCode, serviceType, sourceChannel, granularity));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<PageResponse<ReferenceMarginOrderRow>>> orders(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "ALL") String costCoverage,
        @RequestParam(defaultValue = "completedAt") String sortBy,
        @RequestParam(defaultValue = "desc") String direction) {
        return noStore(service.orders(fromDate, toDate, currencyCode, serviceType, sourceChannel,
            page, size, costCoverage, sortBy, direction));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
