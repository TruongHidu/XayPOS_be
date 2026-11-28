package com.possaas.config;

import com.possaas.common.ratelimit.RateLimitPolicy;
import java.time.Duration;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limits for the public auth endpoints. Per client address: {@code login}, {@code register}, {@code refresh}.
 * Per e-mail: {@code loginFailures} (wrong passwords before the account is temporarily locked).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {
    /** Generous: a whole restaurant often shares one public IP when staff log in at shift start. */
    private Rule login = new Rule(20, Duration.ofMinutes(1));
    /** Counts every attempt, including 400/409 answers, so it also slows e-mail/code probing. */
    private Rule register = new Rule(10, Duration.ofHours(1));
    private Rule refresh = new Rule(60, Duration.ofMinutes(1));
    private Rule loginFailures = new Rule(5, Duration.ofMinutes(15));

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rule {
        private int limit;
        private Duration window;

        public RateLimitPolicy toPolicy(String name) {
            return new RateLimitPolicy(name, limit, window);
        }
    }
}
