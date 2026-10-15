package com.possaas.modules.report.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.report.dto.ReportEnvelope;
import com.possaas.modules.report.dto.TopItemReportResponse;
import com.possaas.modules.report.repository.ReportItemRepository;
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
public class ReportItemQueryService {
    private static final Set<String> SORTS = Set.of("quantity", "itemSalesAmount");
    private static final Set<String> DIRECTIONS = Set.of("asc", "desc");

    private final ReportAccessService access;
    private final ReportRangePolicy ranges;
    private final ReportItemRepository repository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportEnvelope<PageResponse<TopItemReportResponse>> topItems(
        LocalDate fromDate,
        LocalDate toDate,
        String currencyCode,
        ServiceType serviceType,
        SourceChannel sourceChannel,
        int page,
        int size,
        String sortBy,
        String direction
    ) {
        validatePage(page, size);
        String appliedSort = sortBy == null ? "quantity" : sortBy;
        String appliedDirection = direction == null ? "desc" : direction.toLowerCase(Locale.ROOT);
        if (!SORTS.contains(appliedSort) || !DIRECTIONS.contains(appliedDirection)) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_SORT",
                "Unsupported top-items sort options"
            );
        }
        var context = ranges.range(
            access.requireDetailedReport(), fromDate, toDate, currencyCode, serviceType, sourceChannel
        );
        long total = repository.countGroups(context);
        var rows = total == 0
            ? java.util.List.<TopItemReportResponse>of()
            : repository.findPage(context, page, size, appliedSort, appliedDirection);
        return new ReportEnvelope<>(
            context.meta(null, null),
            new PageResponse<>(rows, page, size, total, pages(total, size))
        );
    }

    static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Invalid report pagination"
            );
        }
    }

    static int pages(long total, int size) {
        return total == 0 ? 0 : Math.toIntExact((total - 1) / size + 1);
    }
}
