package com.possaas.common.ratelimit;

import com.possaas.common.exception.BusinessException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/** HTTP 429 raised by services; {@code GlobalExceptionHandler} adds the {@code Retry-After} header. */
public class RateLimitExceededException extends BusinessException {
    private final Duration retryAfter;

    public RateLimitExceededException(String code, String message, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public long retryAfterSeconds() {
        return RateLimitDecision.retryAfterSecondsOf(retryAfter);
    }
}
