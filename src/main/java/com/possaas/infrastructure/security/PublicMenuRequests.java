package com.possaas.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/** Exact read-only routes shared by authorization and all bearer-session filters. */
public final class PublicMenuRequests {
    public static final String RESTAURANT_BASE_PATH = "/api/v1/public/menu/restaurants";
    public static final RequestMatcher GET_ENDPOINTS = new OrRequestMatcher(
            PublicQrMenuRequests.GET_ENDPOINTS,
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, RESTAURANT_BASE_PATH + "/{menuToken}"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, RESTAURANT_BASE_PATH + "/{menuToken}/items"));

    private PublicMenuRequests() {}

    public static boolean matches(HttpServletRequest request) { return GET_ENDPOINTS.matches(request); }
}
