package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.cashbook.service.CashbookRangePolicy;
import com.possaas.modules.restaurant.entity.Restaurant;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class CashbookRangePolicyTest {
    private final Instant now=Instant.parse("2026-10-29T12:00:00Z");
    private final CashbookRangePolicy policy=new CashbookRangePolicy(Clock.fixed(now,ZoneOffset.UTC));
    @Test void boundariesUseLocalCalendarAndInjectedClock() {
        var c=policy.range(restaurant("Asia/Ho_Chi_Minh","VND"),LocalDate.parse("2026-10-29"),LocalDate.parse("2026-10-29"),null);
        assertThat(c.fromInclusive()).isEqualTo(Instant.parse("2026-10-28T17:00:00Z"));
        assertThat(c.toExclusive()).isEqualTo(Instant.parse("2026-10-29T17:00:00Z"));
        assertThat(c.generatedAt()).isEqualTo(now);
        assertThat(c.meta("CASH_OCCURRED_AT",null).dateBasis()).isEqualTo("CASH_OCCURRED_AT");
    }
    @Test void daylightSavingDayDoesNotAssumeTwentyFourHours() {
        var c=policy.range(restaurant("America/New_York","USD"),LocalDate.parse("2026-03-08"),LocalDate.parse("2026-03-08"),null);
        assertThat(Duration.between(c.fromInclusive(),c.toExclusive())).isEqualTo(Duration.ofHours(23));
    }
    @Test void accepts366DaysAndRejects367Days() {
        var r=restaurant("UTC","VND");var day=LocalDate.parse("2026-01-01");
        assertThat(policy.range(r,day,day.plusDays(365),null).toDate()).isEqualTo(day.plusDays(365));
        code(()->policy.range(r,day,day.plusDays(366),null),"CASHBOOK_RANGE_TOO_LARGE");
    }
    @Test void invalidRangeZoneAndCurrencyHaveStableCodes() {
        var r=restaurant("UTC","VND");var day=LocalDate.parse("2026-10-29");
        code(()->policy.range(r,null,day,null),"INVALID_CASHBOOK_DATE_RANGE");
        code(()->policy.range(r,day,day.minusDays(1),null),"INVALID_CASHBOOK_DATE_RANGE");
        code(()->policy.range(r,day,day,"BAD!"),"INVALID_CASHBOOK_CURRENCY");
        r.setTimezone("INVALID/ZONE");code(()->policy.range(r,day,day,null),"INVALID_CASHBOOK_TIMEZONE");
    }
    @Test void explicitHistoricalCurrencyDoesNotUseCurrentDefault() {
        var c=policy.range(restaurant("UTC","VND"),LocalDate.parse("2026-10-29"),LocalDate.parse("2026-10-29")," usd ");
        assertThat(c.currencyCode()).isEqualTo("USD");
    }
    private Restaurant restaurant(String zone,String currency){var r=new Restaurant();r.setId(UUID.randomUUID());r.setTimezone(zone);r.setCurrencyCode(currency);return r;}
    private void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String expected){assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(expected));}
}
