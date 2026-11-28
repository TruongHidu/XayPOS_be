package com.possaas.common.captcha;

import java.util.Objects;
import org.springframework.http.HttpMethod;

/** Requests matching {@code method} + {@code pathPattern} must carry a CAPTCHA token issued for {@code action}. */
public record CaptchaProtectedRequest(HttpMethod method, String pathPattern, String action) {
    public CaptchaProtectedRequest {
        Objects.requireNonNull(method, "method");
        if (pathPattern == null || pathPattern.isBlank()) throw new IllegalArgumentException("pathPattern is required");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action is required");
    }
}
