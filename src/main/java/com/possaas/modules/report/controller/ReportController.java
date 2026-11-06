package com.possaas.modules.report.controller;

import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.PaymentMethodBreakdownResponse;
import com.possaas.modules.report.dto.ReportDateBasis;
import com.possaas.modules.report.dto.ReportEnvelope;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.dto.ReportOrderRowResponse;
import com.possaas.modules.report.dto.ReportOverviewResponse;
import com.possaas.modules.report.dto.RevenueSeriesPointResponse;
import com.possaas.modules.report.dto.TopItemReportResponse;
import com.possaas.modules.report.service.ReportItemQueryService;
import com.possaas.modules.report.service.ReportOrderQueryService;
import com.possaas.modules.report.service.ReportOverviewQueryService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {
    private final ReportOverviewQueryService overview;
    private final ReportItemQueryService items;
    private final ReportOrderQueryService orders;

    @GetMapping("/daily")
    @PreAuthorize("hasAuthority('REPORT_DAILY_VIEW')")
    public ResponseEntity<ReportEnvelope<ReportOverviewResponse>> daily(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel
    ) {
        return noStore(overview.daily(date, currencyCode, serviceType, sourceChannel));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<ReportOverviewResponse>> summary(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel
    ) {
        return noStore(overview.summary(fromDate, toDate, currencyCode, serviceType, sourceChannel));
    }

    @GetMapping("/revenue-series")
    @PreAuthorize("hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<List<RevenueSeriesPointResponse>>> revenueSeries(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false, defaultValue = "DAY") ReportGranularity granularity,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel
    ) {
        return noStore(overview.revenueSeries(
            fromDate, toDate, granularity, currencyCode, serviceType, sourceChannel
        ));
    }

    @GetMapping("/payment-methods")
    @PreAuthorize("hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<List<PaymentMethodBreakdownResponse>>> paymentMethods(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel
    ) {
        return noStore(overview.paymentMethods(
            fromDate, toDate, currencyCode, serviceType, sourceChannel
        ));
    }

    @GetMapping("/top-items")
    @PreAuthorize("hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<PageResponse<TopItemReportResponse>>> topItems(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "quantity") String sortBy,
        @RequestParam(defaultValue = "desc") String direction
    ) {
        return noStore(items.topItems(
            fromDate,
            toDate,
            currencyCode,
            serviceType,
            sourceChannel,
            page,
            size,
            sortBy,
            direction
        ));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<ReportEnvelope<PageResponse<ReportOrderRowResponse>>> orders(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue = "CREATED") ReportDateBasis dateBasis,
        @RequestParam(required = false) String currencyCode,
        @RequestParam(required = false) ServiceType serviceType,
        @RequestParam(required = false) SourceChannel sourceChannel,
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) OrderPaymentStatus paymentStatus,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "reportEventAt") String sortBy,
        @RequestParam(defaultValue = "desc") String direction
    ) {
        return noStore(orders.orders(
            fromDate,
            toDate,
            dateBasis,
            currencyCode,
            serviceType,
            sourceChannel,
            status,
            paymentStatus,
            page,
            size,
            sortBy,
            direction
        ));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
