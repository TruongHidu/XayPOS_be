package com.possaas.modules.report.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReportRangePolicy {
    static final long MAX_INCLUSIVE_DAYS = 366;

    private final Clock clock;

    public ReportQueryContext daily(
        Restaurant restaurant,
        LocalDate requestedDate,
        String requestedCurrency,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        ZoneId zone = zone(restaurant.getTimezone());
        LocalDate date = requestedDate == null
            ? LocalDate.now(clock.withZone(zone))
            : requestedDate;
        return create(restaurant, date, date, requestedCurrency, serviceType, sourceChannel, zone);
    }

    public ReportQueryContext range(
        Restaurant restaurant,
        LocalDate fromDate,
        LocalDate toDate,
        String requestedCurrency,
        ServiceType serviceType,
        SourceChannel sourceChannel
    ) {
        if (fromDate == null || toDate == null) {
            throw invalidRange("fromDate and toDate are required");
        }
        return create(
            restaurant,
            fromDate,
            toDate,
            requestedCurrency,
            serviceType,
            sourceChannel,
            zone(restaurant.getTimezone())
        );
    }

    private ReportQueryContext create(
        Restaurant restaurant,
        LocalDate fromDate,
        LocalDate toDate,
        String requestedCurrency,
        ServiceType serviceType,
        SourceChannel sourceChannel,
        ZoneId zone
    ) {
        if (fromDate.isAfter(toDate)) {
            throw invalidRange("fromDate must not be after toDate");
        }
        long inclusiveDays;
        try {
            inclusiveDays = Math.addExact(ChronoUnit.DAYS.between(fromDate, toDate), 1L);
        } catch (ArithmeticException | DateTimeException failure) {
            throw invalidRange("Report date range is invalid");
        }
        if (inclusiveDays > MAX_INCLUSIVE_DAYS) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "REPORT_RANGE_TOO_LARGE",
                "Report range must not exceed 366 inclusive calendar days"
            );
        }

        try {
            return new ReportQueryContext(
                restaurant.getId(),
                fromDate,
                toDate,
                zone,
                fromDate.atStartOfDay(zone).toInstant(),
                toDate.plusDays(1).atStartOfDay(zone).toInstant(),
                currency(requestedCurrency, restaurant.getCurrencyCode()),
                serviceType,
                sourceChannel,
                clock.instant()
            );
        } catch (DateTimeException failure) {
            throw invalidRange("Report date range is invalid");
        }
    }

    private static ZoneId zone(String value) {
        try {
            return ZoneId.of(value == null ? "" : value.trim());
        } catch (DateTimeException failure) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "INVALID_REPORT_TIMEZONE",
                "Restaurant timezone is invalid"
            );
        }
    }

    private static String currency(String requested, String restaurantDefault) {
        String source = requested == null ? restaurantDefault : requested;
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        try {
            if (!normalized.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("Currency must use an ISO 4217 alphabetic code");
            }
            Currency.getInstance(normalized);
            return normalized;
        } catch (IllegalArgumentException failure) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_REPORT_CURRENCY",
                "Report currency code is invalid"
            );
        }
    }

    private static BusinessException invalidRange(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_DATE_RANGE", message);
    }
}
