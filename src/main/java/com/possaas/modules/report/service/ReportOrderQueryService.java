package com.possaas.modules.report.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.OrderPaymentStatus;
import com.possaas.modules.order.entity.OrderStatus;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.ReportDateBasis;
import com.possaas.modules.report.dto.ReportEnvelope;
import com.possaas.modules.report.dto.ReportOrderRowResponse;
import com.possaas.modules.report.repository.ReportOrderRepository;
import com.possaas.modules.subscription.dto.PageResponse;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReportOrderQueryService {
    private static final Set<String> SORTS = Set.of("reportEventAt", "totalAmount");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final ReportAccessService access;
    private final ReportRangePolicy ranges;
    private final ReportOrderRepository repository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<PageResponse<ReportOrderRowResponse>> orders(
        LocalDate fromDate,
        LocalDate toDate,
        ReportDateBasis dateBasis,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel,
        OrderStatus status,
        OrderPaymentStatus paymentStatus,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        ReportItemQueryService.validatePage(page, size);
        ReportDateBasis appliedBasis = dateBasis == null ? ReportDateBasis.CREATED : dateBasis;
        String appliedSort = sortBy == null ? "reportEventAt" : sortBy;
        String appliedDirection = direction == null ? "desc" : direction.toLowerCase(Locale.ROOT);
        if (!SORTS.contains(appliedSort) || !DIRECTIONS.contains(appliedDirection)) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_SORT",
                "Unsupported report order sort options"
            );
        }
        var context = ranges.range(
            access.requireDetailedReport(), fromDate, toDate, currencyCode, serviceType, sourceChannel
        );
        long total = repository.count(context, appliedBasis, status, paymentStatus);
        var rows = total == 0
            ? java.util.List.<ReportOrderRowResponse>of()
            : repository.findPage(
                context,
                appliedBasis,
                status,
                paymentStatus,
                page,
                size,
                appliedSort,
                appliedDirection
            );
        return new ReportEnvelope<>(
            context.meta(null, appliedBasis),
            new PageResponse<>(rows, page, size, total, ReportItemQueryService.pages(total, size))
        );
    }
}
