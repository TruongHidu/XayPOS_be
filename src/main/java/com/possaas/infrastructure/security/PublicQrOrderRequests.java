package com.possaas.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Guest capability authorization happens in QR services, never in the staff JWT principal. */
public final class PublicQrOrderRequests {
    public static final RequestMatcher ENDPOINTS = new OrRequestMatcher(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/public/qr-order/tables/{qrToken}/guest-access"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/public/qr-order/restaurants/{menuToken}/guest-access"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/public/qr-order/requests"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/public/qr-order/requests/{requestId}"));
    private PublicQrOrderRequests() {}
    public static boolean matches(HttpServletRequest request) { return ENDPOINTS.matches(request); }
}
