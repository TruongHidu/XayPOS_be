package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.service.ReportBucketPolicy;
import com.possaas.modules.report.service.ReportRangePolicy;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportRangePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-15T23:30:00Z");

    @Test
    void defaultDailyDateUsesRestaurantTimezoneAndInjectedClock() {
        var restaurant = restaurant("Asia/Tokyo", "VND");
        var context = policy().daily(restaurant, null, null, null, null);
        assertThat(context.fromDate()).isEqualTo(LocalDate.parse("2026-10-16"));
        assertThat(context.toDate()).isEqualTo(context.fromDate());
        assertThat(context.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void dayBoundariesUseZoneRulesInsteadOfFixedTwentyFourHours() {
        var restaurant = restaurant("America/New_York", "USD");
        var context = policy().range(
            restaurant,
            LocalDate.parse("2026-03-08"),
            LocalDate.parse("2026-03-08"),
            null,
            null,
            null
        );
        assertThat(Duration.between(context.fromInclusive(), context.toExclusive())).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void rangeAllowsAtMostThreeHundredSixtySixInclusiveDays() {
        var restaurant = restaurant("UTC", "VND");
        var from = LocalDate.parse("2026-01-01");
        assertThat(policy().range(restaurant, from, from.plusDays(365), null, null, null).toDate())
            .isEqualTo(from.plusDays(365));
        assertThatThrownBy(() -> policy().range(restaurant, from, from.plusDays(366), null, null, null))
            .isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getCode()).isEqualTo("REPORT_RANGE_TOO_LARGE"));
    }

    @Test
    void invalidRangeTimezoneAndCurrencyHaveStableBusinessCodes() {
        var restaurant = restaurant("UTC", "VND");
        assertCode(() -> policy().range(
            restaurant, LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-01"), null, null, null
        ), "INVALID_REPORT_DATE_RANGE");
        restaurant.setTimezone("Not/A_Zone");
        assertCode(() -> policy().daily(restaurant, null, null, null, null), "INVALID_REPORT_TIMEZONE");
        restaurant.setTimezone("UTC");
        assertCode(() -> policy().daily(restaurant, null, "NOPE", null, null), "INVALID_REPORT_CURRENCY");
    }

    @Test
    void currencyIsUppercasedAndDayMonthBucketsAreGapReady() {
        var context = policy().range(
            restaurant("UTC", "VND"),
            LocalDate.parse("2026-01-30"),
            LocalDate.parse("2026-03-02"),
            "usd",
            null,
            null
        );
        assertThat(context.currencyCode()).isEqualTo("USD");
        var buckets = new ReportBucketPolicy();
        assertThat(buckets.buckets(context, ReportGranularity.MONTH)).containsExactly(
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-02-01"),
            LocalDate.parse("2026-03-01")
        );
        assertThat(buckets.buckets(context, ReportGranularity.DAY)).hasSize(32)
            .first().isEqualTo(LocalDate.parse("2026-01-30"));
    }

    private static ReportRangePolicy policy() {
        return new ReportRangePolicy(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Restaurant restaurant(String timezone, String currency) {
        var restaurant = new Restaurant();
        restaurant.setId(java.util.UUID.randomUUID());
        restaurant.setTimezone(timezone);
        restaurant.setCurrencyCode(currency);
        return restaurant;
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, failure ->
            assertThat(failure.getCode()).isEqualTo(code));
    }
}
