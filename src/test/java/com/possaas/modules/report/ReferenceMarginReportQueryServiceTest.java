package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository;
import com.possaas.modules.report.service.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class ReferenceMarginReportQueryServiceTest {
    @Test void deniedAccessStopsBeforeRangeRepositoryAndCalculator() {
        var access = mock(ReferenceMarginReportAccess.class);
        var ranges = mock(ReportRangePolicy.class);
        var buckets = mock(ReportBucketPolicy.class);
        var repository = mock(ReferenceMarginReportRepository.class);
        var policy = mock(ReferenceMarginPolicy.class);
        var service = new ReferenceMarginReportQueryService(access, ranges, buckets, repository, policy);
        when(access.requireRead()).thenThrow(new AccessDeniedException("denied"));
        assertThatThrownBy(() -> service.summary(LocalDate.now(), LocalDate.now(), "VND", null, null))
            .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(ranges, buckets, repository, policy);
    }
}
