package com.possaas.modules.auth.controller;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.auth.dto.*;
import com.possaas.modules.auth.service.AuthenticationService;
import com.possaas.modules.restaurant.service.RestaurantRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final RestaurantRegistrationService registrationService;
    private final AuthenticationService authenticationService;

    @PostMapping("/register-restaurant")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRestaurantRequest request, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(registrationService.register(request, http.getRemoteAddr()));
    }

    @PostMapping("/login")
    ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.login(request, http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(authenticationService.refresh(request, http.getRemoteAddr()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request, Authentication authentication, HttpServletRequest http) {
        CurrentUser current = (CurrentUser) authentication.getPrincipal();
        authenticationService.logout(current.userId(), request, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ResponseEntity<MeResponse> me(Authentication authentication) {
        CurrentUser current = (CurrentUser) authentication.getPrincipal();
        return ResponseEntity.ok(authenticationService.me(current.userId()));
    }
}
