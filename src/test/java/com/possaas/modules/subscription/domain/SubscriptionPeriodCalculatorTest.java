package com.possaas.modules.subscription.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SubscriptionPeriodCalculatorTest {
    private static final String ZONE = "Asia/Ho_Chi_Minh";

    @Test void addsCalendarMonthsInBillingZoneAndClampsMonthEnd() {
        // 31 Jan 10:00 Vietnam time → last day of February.
        Instant jan31 = Instant.parse("2027-01-31T03:00:00Z");
        assertThat(SubscriptionPeriodCalculator.endAt(jan31, ZONE, 1)).isEqualTo(Instant.parse("2027-02-28T03:00:00Z"));
        assertThat(SubscriptionPeriodCalculator.endAt(Instant.parse("2028-01-31T03:00:00Z"), ZONE, 1)).isEqualTo(Instant.parse("2028-02-29T03:00:00Z"));
        assertThat(SubscriptionPeriodCalculator.endAt(jan31, ZONE, 12)).isEqualTo(Instant.parse("2028-01-31T03:00:00Z"));
    }

    @Test void usesBillingZoneNotUtcAtDayBoundary() {
        // 30 Nov 20:00 UTC is already 1 Dec in Vietnam, so one month later is 1 Jan, not 30 Dec.
        assertThat(SubscriptionPeriodCalculator.endAt(Instant.parse("2026-11-30T20:00:00Z"), ZONE, 1))
            .isEqualTo(Instant.parse("2026-12-31T20:00:00Z"));
        assertThat(SubscriptionPeriodCalculator.endAt(Instant.parse("2026-10-31T20:00:00Z"), ZONE, 1))
            .isEqualTo(Instant.parse("2026-11-30T20:00:00Z"));
    }

    @Test void rejectsEmptyCycle() {
        assertThatThrownBy(() -> SubscriptionPeriodCalculator.endAt(Instant.EPOCH, ZONE, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
