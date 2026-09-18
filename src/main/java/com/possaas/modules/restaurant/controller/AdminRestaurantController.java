package com.possaas.modules.restaurant.controller;

import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.restaurant.dto.AdminRestaurantDetailResponse;
import com.possaas.modules.restaurant.dto.AdminRestaurantSummaryResponse;
import com.possaas.modules.restaurant.dto.UpdateRestaurantStatusRequest;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.service.RestaurantAdminCommandService;
import com.possaas.modules.restaurant.service.RestaurantAdminQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/restaurants")
@RequiredArgsConstructor
public class AdminRestaurantController {
    private final RestaurantAdminQueryService queryService;
    private final RestaurantAdminCommandService commandService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_VIEW')"
    )
    ResponseEntity<PageResponse<AdminRestaurantSummaryResponse>> search(
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(required = false) RestaurantStatus status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "desc") String direction
    ) {
        return ResponseEntity.ok(queryService.search(q, status, page, size, sortBy, direction));
    }

    @GetMapping("/{restaurantId}")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_VIEW')"
    )
    ResponseEntity<AdminRestaurantDetailResponse> detail(@PathVariable UUID restaurantId) {
        return ResponseEntity.ok(queryService.findDetail(restaurantId));
    }

    @PatchMapping("/{restaurantId}/status")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_MANAGE')"
    )
    ResponseEntity<AdminRestaurantDetailResponse> updateStatus(
        @PathVariable UUID restaurantId,
        @Valid @RequestBody UpdateRestaurantStatusRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(commandService.updateStatus(
            restaurantId,
            request,
            actor.userId(),
            httpRequest.getRemoteAddr()
        ));
    }
}
