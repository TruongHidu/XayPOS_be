package com.possaas.modules.restaurant.controller;

import com.possaas.modules.restaurant.dto.*;
import com.possaas.modules.restaurant.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/me")
public class RestaurantProfileController {
    private final RestaurantProfileQueryService query;
    private final RestaurantProfileCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_VIEW')")
    public RestaurantProfileResponse get() { return query.get(); }

    @PatchMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_UPDATE')")
    public RestaurantProfileResponse update(@Valid @RequestBody UpdateRestaurantProfileRequest request, HttpServletRequest http) {
        return command.update(request, http.getRemoteAddr());
    }
}
