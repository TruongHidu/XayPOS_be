package com.possaas.modules.report.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.*;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReferenceMarginReportQueryService {
    private static final Set<String> SORTS = Set.of("completedAt", "netSalesAmount", "referenceCostAmount", "referenceMarginAmount");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> COVERAGES = Set.of("ALL", "COMPLETE", "EXCLUDED");
    private final ReferenceMarginReportAccess access;
    private final ReportRangePolicy ranges;
    private final ReportBucketPolicy buckets;
    private final ReferenceMarginReportRepository repository;
    private final ReferenceMarginPolicy policy;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<ReferenceMarginSummary> summary(LocalDate from, LocalDate to, String currency,
            ServiceType type, SourceChannel channel) {
        var context = context(from, to, currency, type, channel);
        repository.assertConsistent(context);
        return new ReportEnvelope<>(context.meta(null, ReportDateBasis.COMPLETED), policy.summarize(repository.aggregate(context)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<List<ReferenceMarginSeriesPoint>> series(LocalDate from, LocalDate to, String currency,
            ServiceType type, SourceChannel channel, ReportGranularity granularity) {
        var context = context(from, to, currency, type, channel);
        var applied = granularity == null ? ReportGranularity.DAY : granularity;
        repository.assertConsistent(context);
        var byDate = repository.buckets(context, applied).stream().collect(Collectors.toMap(
            ReferenceMarginReportRepository.Bucket::date, Function.identity()));
        var points = buckets.buckets(context, applied).stream().map(date -> {
            var bucket = byDate.get(date);
            return new ReferenceMarginSeriesPoint(date, bucket == null ? policy.empty() : policy.summarize(bucket.aggregate()));
        }).toList();
        return new ReportEnvelope<>(context.meta(applied, ReportDateBasis.COMPLETED), points);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<PageResponse<ReferenceMarginOrderRow>> orders(LocalDate from, LocalDate to, String currency,
            ServiceType type, SourceChannel channel, int page, int size, String coverage, String sortBy, String direction) {
        var context = context(from, to, currency, type, channel);
        ReportItemQueryService.validatePage(page, size);
        String sort = sortBy == null ? "completedAt" : sortBy;
        String dir = direction == null ? "desc" : direction.toLowerCase(Locale.ROOT);
        String filter = coverage == null ? "ALL" : coverage.toUpperCase(Locale.ROOT);
        if (!SORTS.contains(sort) || !DIRECTIONS.contains(dir))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_SORT", "Unsupported report sort options");
        if (!COVERAGES.contains(filter))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported cost coverage");
        repository.assertConsistent(context);
        long total = repository.count(context, filter);
        var rows = total == 0 ? List.<ReferenceMarginOrderRow>of() : repository.page(context, filter, page, size, sort, dir)
            .stream().map(policy::order).toList();
        return new ReportEnvelope<>(context.meta(null, ReportDateBasis.COMPLETED),
            new PageResponse<>(rows, page, size, total, ReportItemQueryService.pages(total, size)));
    }

    private ReportQueryContext context(LocalDate from, LocalDate to, String currency, ServiceType type, SourceChannel channel) {
        return ranges.range(access.requireRead(), from, to, currency, type, channel);
    }
}
