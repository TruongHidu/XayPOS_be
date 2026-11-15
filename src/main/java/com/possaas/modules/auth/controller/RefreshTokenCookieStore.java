package com.possaas.modules.auth.controller;

import com.possaas.config.RefreshCookieProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** Reads and writes the HttpOnly refresh-token cookie; knows nothing about when it should be used. */
@Component
@RequiredArgsConstructor
class RefreshTokenCookieStore {
    private final RefreshCookieProperties properties;

    Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
            .filter(cookie -> properties.getName().equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> !value.isBlank())
            .findFirst();
    }

    void write(HttpServletResponse response, String token, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, maxAge).toString());
    }

    void clear(HttpServletResponse response) {
        write(response, "", Duration.ZERO);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.getName(), value)
            .httpOnly(true)
            .secure(properties.isSecure())
            .sameSite(properties.getSameSite())
            .path(properties.getPath())
            .maxAge(maxAge)
            .build();
    }
}
