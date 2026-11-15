package com.possaas.modules.auth.controller;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.auth.dto.*;
import com.possaas.modules.auth.service.AuthenticationService;
import com.possaas.modules.restaurant.service.RestaurantRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Login, registration, refresh and logout. How the refresh token travels (JSON body for the mobile app, HttpOnly
 * cookie for browsers) is decided by {@link AuthTokenTransport}; the services only deal with raw tokens.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final RestaurantRegistrationService registrationService;
    private final AuthenticationService authenticationService;
    private final AuthTokenTransport transport;

    @PostMapping("/register-restaurant")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRestaurantRequest request, HttpServletRequest http,
            HttpServletResponse response) {
        AuthResponse issued = registrationService.register(request, http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(transport.deliver(issued, http, response));
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http,
            HttpServletResponse response) {
        AuthResponse issued = authenticationService.login(request, http.getRemoteAddr());
        return ResponseEntity.ok(transport.deliver(issued, http, response));
    }

    /** Body is optional: browsers in cookie mode send no token in JSON. */
    @PostMapping("/refresh")
    ResponseEntity<AuthResponse> refresh(@RequestBody(required = false) RefreshRequest request, HttpServletRequest http,
            HttpServletResponse response) {
        Optional<String> token = transport.presentedRefreshToken(http, bodyToken(request));
        if (token.isEmpty()) {
            transport.forget(http, response);
            throw invalidRefreshToken();
        }
        try {
            AuthResponse issued = authenticationService.refresh(new RefreshRequest(token.get()), http.getRemoteAddr());
            return ResponseEntity.ok(transport.deliver(issued, http, response));
        } catch (BusinessException rejected) {
            // A dead cookie would otherwise be replayed on every page load.
            if (rejected.getStatus() == HttpStatus.UNAUTHORIZED) transport.forget(http, response);
            throw rejected;
        }
    }

    /** Possession of the refresh token is the proof, so an expired access token does not block logging out. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request, Authentication authentication,
            HttpServletRequest http, HttpServletResponse response) {
        UUID userId = authentication != null && authentication.getPrincipal() instanceof CurrentUser current
            ? current.userId() : null;
        Optional<String> token = transport.presentedRefreshToken(http, bodyToken(request));
        if (userId == null && token.isEmpty() && !transport.usesCookie(http))
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
        transport.forget(http, response);
        token.ifPresent(value -> authenticationService.logout(userId, value, http.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ResponseEntity<MeResponse> me(Authentication authentication) {
        CurrentUser current = (CurrentUser) authentication.getPrincipal();
        return ResponseEntity.ok(authenticationService.me(current.userId()));
    }

    private static String bodyToken(RefreshRequest request) {
        return request == null ? null : request.refreshToken();
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid refresh token");
    }
}
