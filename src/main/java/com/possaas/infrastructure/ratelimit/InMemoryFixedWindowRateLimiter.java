package com.possaas.infrastructure.ratelimit;

import com.possaas.common.ratelimit.RateLimitDecision;
import com.possaas.common.ratelimit.RateLimitPolicy;
import com.possaas.common.ratelimit.RateLimiter;
import com.possaas.config.RateLimitProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters kept in this JVM, so limits hold for a single backend instance only. Each policy has its
 * own bounded store keyed by a SHA-256 digest of the caller's key: no raw e-mail or IP stays in memory. A full
 * store first drops expired windows; if it is still full, new keys are rejected rather than evicting live counters,
 * which would let an attacker reset its own limit by flooding fresh keys.
 */
@Component
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InMemoryFixedWindowRateLimiter implements RateLimiter {
    private final Clock clock;
    private final int maxEntriesPerPolicy;
    private final ConcurrentMap<String, PolicyStore> stores = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryFixedWindowRateLimiter(Clock clock, RateLimitProperties properties) {
        this(clock, properties.getMaxEntriesPerPolicy());
    }

    InMemoryFixedWindowRateLimiter(Clock clock, int maxEntriesPerPolicy) {
        if (maxEntriesPerPolicy < 1) throw new IllegalArgumentException("app.rate-limit.max-entries-per-policy must be positive");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxEntriesPerPolicy = maxEntriesPerPolicy;
    }

    @Override
    public RateLimitDecision tryAcquire(RateLimitPolicy policy, String key) {
        return store(policy).tryAcquire(policy, digest(key), clock.millis());
    }

    @Override
    public RateLimitDecision check(RateLimitPolicy policy, String key) {
        return store(policy).check(policy, digest(key), clock.millis());
    }

    @Override
    public void reset(RateLimitPolicy policy, String key) {
        store(policy).reset(digest(key));
    }

    private PolicyStore store(RateLimitPolicy policy) {
        return stores.computeIfAbsent(policy.name(), name -> new PolicyStore(maxEntriesPerPolicy));
    }

    private static String digest(String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(Objects.toString(key, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class PolicyStore {
        private final int maxEntries;
        private final Map<String, Window> windows = new HashMap<>();

        PolicyStore(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        synchronized RateLimitDecision tryAcquire(RateLimitPolicy policy, String key, long now) {
            Window current = live(policy, key, now);
            if (current == null) {
                if (windows.size() >= maxEntries) purgeExpired(policy, now);
                if (windows.size() >= maxEntries) return RateLimitDecision.rejected(policy.window());
                windows.put(key, new Window(now, 1));
                return RateLimitDecision.allowed(policy.limit() - 1);
            }
            if (current.count >= policy.limit()) return RateLimitDecision.rejected(current.remaining(policy, now));
            current.count++;
            return RateLimitDecision.allowed(policy.limit() - current.count);
        }

        synchronized RateLimitDecision check(RateLimitPolicy policy, String key, long now) {
            Window current = live(policy, key, now);
            if (current == null) return RateLimitDecision.allowed(policy.limit());
            if (current.count >= policy.limit()) return RateLimitDecision.rejected(current.remaining(policy, now));
            return RateLimitDecision.allowed(policy.limit() - current.count);
        }

        synchronized void reset(String key) {
            windows.remove(key);
        }

        /** The key's window if it is still open; an expired one is dropped. */
        private Window live(RateLimitPolicy policy, String key, long now) {
            Window window = windows.get(key);
            if (window != null && window.expired(policy, now)) {
                windows.remove(key);
                return null;
            }
            return window;
        }

        private void purgeExpired(RateLimitPolicy policy, long now) {
            windows.values().removeIf(window -> window.expired(policy, now));
        }
    }

    private static final class Window {
        private final long startMillis;
        private int count;

        Window(long startMillis, int count) {
            this.startMillis = startMillis;
            this.count = count;
        }

        boolean expired(RateLimitPolicy policy, long now) {
            return now >= startMillis + policy.window().toMillis();
        }

        Duration remaining(RateLimitPolicy policy, long now) {
            return Duration.ofMillis(startMillis + policy.window().toMillis() - now);
        }
    }
}
