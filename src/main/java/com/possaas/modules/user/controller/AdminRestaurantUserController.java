package com.possaas.modules.user.controller;

import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.user.dto.AdminRestaurantUserResponse;
import com.possaas.modules.user.service.AdminRestaurantUserQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/restaurants/{restaurantId}/users")
@PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_VIEW')")
public class AdminRestaurantUserController {
    private final AdminRestaurantUserQueryService queryService;

    @GetMapping
    ResponseEntity<PageResponse<AdminRestaurantUserResponse>> search(
            @PathVariable UUID restaurantId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {
        return ResponseEntity.ok(queryService.search(restaurantId, q, roleCode, active, page, size, sortBy, direction));
    }

    @GetMapping("/{userId}")
    ResponseEntity<AdminRestaurantUserResponse> detail(@PathVariable UUID restaurantId, @PathVariable UUID userId) {
        return ResponseEntity.ok(queryService.findDetail(restaurantId, userId));
    }
}
