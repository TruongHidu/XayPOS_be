package com.possaas.infrastructure.security;

import com.possaas.common.ratelimit.ClientAddressKey;
import com.possaas.common.ratelimit.RateLimitDecision;
import com.possaas.common.ratelimit.RateLimiter;
import com.possaas.common.ratelimit.RequestRateLimitRule;
import com.possaas.common.ratelimit.RequestRateLimitRules;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies every {@link RequestRateLimitRules} rule per client address. It sits in the security chain right after
 * CORS (see {@code SecurityConfig}) so a 429 still carries CORS headers, and before anything parses the body.
 * The address comes from {@code getRemoteAddr()}, which is the real client only when forwarded headers are trusted.
 */
@Component
public class RequestRateLimitFilter extends OncePerRequestFilter {
    static final String CODE = "TOO_MANY_REQUESTS";
    static final String MESSAGE = "Too many requests. Please try again later.";

    private final List<MatchedRule> rules;
    private final RateLimiter limiter;
    private final SecurityErrorResponseWriter errorWriter;

    public RequestRateLimitFilter(List<RequestRateLimitRules> providers, RateLimiter limiter,
            SecurityErrorResponseWriter errorWriter) {
        this.rules = providers.stream()
            .flatMap(provider -> provider.rules().stream())
            .map(MatchedRule::of)
            .toList();
        this.limiter = limiter;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rules.stream().noneMatch(rule -> rule.matcher().matches(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = ClientAddressKey.of(request.getRemoteAddr());
        for (MatchedRule rule : rules) {
            if (!rule.matcher().matches(request)) continue;
            RateLimitDecision decision = limiter.tryAcquire(rule.rule().policy(), client);
            if (!decision.allowed()) {
                reject(response, decision);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        // The writer resets only the body buffer, so these headers survive.
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        errorWriter.write(response, HttpStatus.TOO_MANY_REQUESTS, CODE, MESSAGE);
    }

    private record MatchedRule(RequestRateLimitRule rule, RequestMatcher matcher) {
        static MatchedRule of(RequestRateLimitRule rule) {
            return new MatchedRule(rule, PathPatternRequestMatcher.withDefaults().matcher(rule.method(), rule.pathPattern()));
        }
    }
}
