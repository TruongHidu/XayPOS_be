package com.possaas.infrastructure.security;

import com.possaas.common.captcha.CaptchaProtectedRequest;
import com.possaas.common.captcha.CaptchaProtectedRequests;
import com.possaas.common.captcha.CaptchaResult;
import com.possaas.common.captcha.CaptchaVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a valid CAPTCHA token (header {@value #TOKEN_HEADER}) on every {@link CaptchaProtectedRequests} request.
 * It runs right after the rate-limit filter (see {@code SecurityConfig}), so a flood is rejected before it costs a
 * call to the provider, and before anything parses the body.
 */
@Slf4j
@Component
public class CaptchaVerificationFilter extends OncePerRequestFilter {
    public static final String TOKEN_HEADER = "X-Captcha-Token";

    private final List<MatchedRequest> protectedRequests;
    private final CaptchaVerifier verifier;
    private final SecurityErrorResponseWriter errorWriter;

    public CaptchaVerificationFilter(List<CaptchaProtectedRequests> providers, CaptchaVerifier verifier,
            SecurityErrorResponseWriter errorWriter) {
        this.protectedRequests = providers.stream()
            .flatMap(provider -> provider.requests().stream())
            .map(MatchedRequest::of)
            .toList();
        this.verifier = verifier;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return match(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MatchedRequest matched = match(request);
        CaptchaResult result = verifier.verify(request.getHeader(TOKEN_HEADER), request.getRemoteAddr(),
            matched.request().action());
        switch (result.outcome()) {
            case PASSED -> chain.doFilter(request, response);
            case MISSING -> reject(response, HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED",
                "Please complete the CAPTCHA challenge.");
            case REJECTED -> {
                log.info("CAPTCHA rejected for {}: {}", matched.request().action(), result.reasons());
                reject(response, HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID",
                    "CAPTCHA verification failed. Please try again.");
            }
            case UNAVAILABLE -> reject(response, HttpStatus.SERVICE_UNAVAILABLE, "CAPTCHA_UNAVAILABLE",
                "CAPTCHA verification is temporarily unavailable. Please try again later.");
        }
    }

    private MatchedRequest match(HttpServletRequest request) {
        for (MatchedRequest candidate : protectedRequests) {
            if (candidate.matcher().matches(request)) return candidate;
        }
        return null;
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        errorWriter.write(response, status, code, message);
    }

    private record MatchedRequest(CaptchaProtectedRequest request, RequestMatcher matcher) {
        static MatchedRequest of(CaptchaProtectedRequest request) {
            return new MatchedRequest(request,
                PathPatternRequestMatcher.withDefaults().matcher(request.method(), request.pathPattern()));
        }
    }
}
