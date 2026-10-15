package com.possaas.modules.report.service;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.payment.entity.PaymentMethod;
import com.possaas.modules.report.dto.PaymentMethodBreakdownResponse;
import com.possaas.modules.report.dto.ReportEnvelope;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.dto.ReportOverviewResponse;
import com.possaas.modules.report.dto.ReportOverviewResponse.OrderMetrics;
import com.possaas.modules.report.dto.RevenueSeriesPointResponse;
import com.possaas.modules.report.repository.ReportOverviewRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportOverviewQueryService {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ReportAccessService access;
    private final ReportRangePolicy ranges;
    private final ReportBucketPolicy buckets;
    private final ReportOverviewRepository repository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<ReportOverviewResponse> daily(
        LocalDate date,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        var context = ranges.daily(access.requireDailyReport(), date, currencyCode, serviceType, sourceChannel);
        return new ReportEnvelope<>(context.meta(null, null), overview(context));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<ReportOverviewResponse> summary(
        LocalDate fromDate,
        LocalDate toDate,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        var context = ranges.range(
            access.requireDetailedReport(), fromDate, toDate, currencyCode, serviceType, sourceChannel
        );
        return new ReportEnvelope<>(context.meta(null, null), overview(context));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<List<RevenueSeriesPointResponse>> revenueSeries(
        LocalDate fromDate,
        LocalDate toDate,
        ReportGranularity granularity,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        ReportGranularity applied = granularity == null ? ReportGranularity.DAY : granularity;
        var context = ranges.range(
            access.requireDetailedReport(), fromDate, toDate, currencyCode, serviceType, sourceChannel
        );
        var collected = repository.collectedSeries(context, applied).stream()
            .collect(Collectors.toMap(ReportOverviewRepository.CollectedBucket::date, Function.identity()));
        var completed = repository.completedSeries(context, applied).stream()
            .collect(Collectors.toMap(ReportOverviewRepository.CompletedBucket::date, Function.identity()));
        var data = buckets.buckets(context, applied).stream().map(date -> {
            var payment = collected.get(date);
            var order = completed.get(date);
            return new RevenueSeriesPointResponse(
                date,
                payment == null ? money(BigDecimal.ZERO) : money(payment.amount()),
                order == null ? money(BigDecimal.ZERO) : money(order.amount()),
                payment == null ? 0L : payment.paymentCount(),
                order == null ? 0L : order.orderCount()
            );
        }).toList();
        return new ReportEnvelope<>(context.meta(applied, null), data);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<List<PaymentMethodBreakdownResponse>> paymentMethods(
        LocalDate fromDate,
        LocalDate toDate,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        var context = ranges.range(
            access.requireDetailedReport(), fromDate, toDate, currencyCode, serviceType, sourceChannel
        );
        var values = new EnumMap<PaymentMethod, ReportOverviewRepository.PaymentMethodAggregate>(PaymentMethod.class);
        repository.paymentMethods(context).forEach(row -> values.put(row.method(), row));
        BigDecimal total = values.values().stream()
            .map(ReportOverviewRepository.PaymentMethodAggregate::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        var data = List.of(PaymentMethod.CASH, PaymentMethod.TRANSFER).stream().map(method -> {
            var row = values.get(method);
            BigDecimal amount = row == null ? BigDecimal.ZERO : row.amount();
            BigDecimal percentage = total.signum() == 0
                ? BigDecimal.ZERO.setScale(2)
                : amount.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
            return new PaymentMethodBreakdownResponse(
                method,
                money(amount),
                row == null ? 0L : row.paymentCount(),
                percentage
            );
        }).toList();
        return new ReportEnvelope<>(context.meta(null, null), data);
    }

    private ReportOverviewResponse overview(ReportQueryContext context) {
        var collections = repository.collections(context);
        var completed = repository.completedOrders(context);
        BigDecimal average = completed.orderCount() == 0
            ? BigDecimal.ZERO.setScale(2)
            : completed.salesAmount().divide(BigDecimal.valueOf(completed.orderCount()), 2, RoundingMode.HALF_UP);
        return new ReportOverviewResponse(
            new ReportOverviewResponse.CollectionsMetrics(
                money(collections.collectedAmount()),
                collections.successfulPaymentCount(),
                collections.paidOrderCount()
            ),
            new OrderMetrics(
                repository.createdOrderCount(context),
                completed.orderCount(),
                repository.cancelledOrderCount(context),
                money(completed.subtotalAmount()),
                money(completed.discountAmount()),
                money(completed.taxAmount()),
                money(completed.serviceChargeAmount()),
                money(completed.salesAmount()),
                average
            )
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
