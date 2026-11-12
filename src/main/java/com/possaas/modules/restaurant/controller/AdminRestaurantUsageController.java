package com.possaas.modules.restaurant.controller;

import com.possaas.modules.restaurant.dto.AdminRestaurantUsageResponse;
import com.possaas.modules.restaurant.service.AdminRestaurantUsageService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/restaurants/{restaurantId}/usage")
@RequiredArgsConstructor
public class AdminRestaurantUsageController {
    private final AdminRestaurantUsageService usage;

    @GetMapping
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_VIEW')")
    ResponseEntity<AdminRestaurantUsageResponse> usage(@PathVariable UUID restaurantId) {
        return ResponseEntity.ok(usage.usage(restaurantId));
    }
}
