package com.possaas.modules.auth.controller;

import com.possaas.config.JwtProperties;
import com.possaas.modules.auth.dto.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides how the refresh token travels for one request.
 *
 * <p>Browsers send {@code X-Auth-Transport: cookie}: the token goes into an HttpOnly, SameSite cookie scoped to the
 * auth endpoints and is removed from the JSON body, so page scripts never see it. The custom header also forces a
 * CORS preflight, which together with SameSite blocks cross-site requests while CSRF protection is disabled.
 * Every other client (the mobile app) keeps sending and receiving the token in the JSON body.
 */
@Component
@RequiredArgsConstructor
class AuthTokenTransport {
    static final String HEADER = "X-Auth-Transport";
    static final String COOKIE_MODE = "cookie";

    private final RefreshTokenCookieStore cookies;
    private final JwtProperties jwt;

    boolean usesCookie(HttpServletRequest request) {
        return COOKIE_MODE.equalsIgnoreCase(request.getHeader(HEADER));
    }

    /** Body to return after login, registration or refresh. */
    AuthResponse deliver(AuthResponse issued, HttpServletRequest request, HttpServletResponse response) {
        if (!usesCookie(request)) return issued;
        cookies.write(response, issued.refreshToken(), jwt.getRefreshTokenTtl());
        return issued.withoutRefreshToken();
    }

    /** Token presented by the client: the cookie in cookie mode, otherwise the JSON body. */
    Optional<String> presentedRefreshToken(HttpServletRequest request, String bodyToken) {
        if (usesCookie(request)) return cookies.read(request);
        return Optional.ofNullable(bodyToken).filter(token -> !token.isBlank());
    }

    /** Removes the cookie of a browser client; no-op for body clients. */
    void forget(HttpServletRequest request, HttpServletResponse response) {
        if (usesCookie(request)) cookies.clear(response);
    }
}
