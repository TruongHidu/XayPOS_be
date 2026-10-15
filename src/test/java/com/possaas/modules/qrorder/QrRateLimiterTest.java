package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import com.possaas.modules.qrorder.service.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrRateLimiterTest {
    private final MutableClock clock=new MutableClock();
    @Test void limitsMintByIpAndScopeAndSubmissionByGuestAndScopeIndependently() {
        var limiter=new QrRateLimiter(clock,1,1,10,60); var guest=UUID.randomUUID();
        limiter.mint("127.0.0.1","table-a");
        assertThatThrownBy(()->limiter.mint("127.0.0.1","table-a")).isInstanceOf(QrRateLimitException.class);
        assertThatCode(()->limiter.mint("127.0.0.1","table-b")).doesNotThrowAnyException();
        limiter.submit(guest,"table-a");
        assertThatThrownBy(()->limiter.submit(guest,"table-a")).isInstanceOf(QrRateLimitException.class);
        assertThatCode(()->limiter.submit(UUID.randomUUID(),"table-a")).doesNotThrowAnyException();
        assertThat(limiter.retryAfterSeconds()).isEqualTo(60);
    }
    @Test void expiredWindowsArePrunedAndLiveCountersCannotBeEvictedToBypassBound() {
        var limiter=new QrRateLimiter(clock,1,1,1,60); limiter.mint("ip","scope");
        assertThatThrownBy(()->limiter.mint("other","scope")).isInstanceOf(QrRateLimitException.class);
        assertThatThrownBy(()->limiter.mint("ip","scope")).isInstanceOf(QrRateLimitException.class);
        clock.now=clock.now.plusSeconds(60);
        assertThatCode(()->limiter.mint("other","scope")).doesNotThrowAnyException();
    }
    @Test void rejectsInvalidConfiguration() {
        assertThatThrownBy(()->new QrRateLimiter(clock,0,1,10,60)).isInstanceOf(IllegalArgumentException.class);
    }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-14T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
