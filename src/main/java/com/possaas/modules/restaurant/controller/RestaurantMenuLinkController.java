package com.possaas.modules.restaurant.controller;

import com.possaas.modules.restaurant.dto.*;
import com.possaas.modules.restaurant.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/me/menu-link")
@PreAuthorize("hasAuthority('RESTAURANT_PROFILE_UPDATE')")
public class RestaurantMenuLinkController {
    private final RestaurantMenuLinkQueryService query;
    private final RestaurantMenuLinkCommandService command;

    @GetMapping
    public ResponseEntity<RestaurantMenuLinkResponse> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.get());
    }

    @PostMapping
    public ResponseEntity<RestaurantMenuLinkResponse> initialize(HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(command.initialize(http.getRemoteAddr()));
    }

    @PostMapping("/rotate")
    public ResponseEntity<RestaurantMenuLinkResponse> rotate(@Valid @RequestBody RotateRestaurantMenuLinkRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(command.rotate(request, http.getRemoteAddr()));
    }
}
