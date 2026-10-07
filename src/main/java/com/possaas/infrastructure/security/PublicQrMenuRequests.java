package com.possaas.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** The same narrow boundary is used by authorization and all bearer-session filters. */
public final class PublicQrMenuRequests {
    public static final String BASE_PATH = "/api/v1/public/qr-menu";
    public static final RequestMatcher GET_ENDPOINTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE_PATH + "/{qrToken}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, BASE_PATH + "/{qrToken}/items"));

    private PublicQrMenuRequests() {}

    public static boolean matches(HttpServletRequest request) {
        return GET_ENDPOINTS.matches(request);
    }
}
