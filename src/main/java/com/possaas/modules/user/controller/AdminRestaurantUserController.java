package com.possaas.modules.user.controller;

import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.user.dto.AdminUserSupportDtos.TemporaryPasswordRequest;
import com.possaas.modules.user.dto.AdminUserSupportDtos.TemporaryPasswordResponse;
import com.possaas.modules.user.dto.AdminUserSupportDtos.UpdateUserStatusRequest;
import com.possaas.modules.user.service.AdminRestaurantUserCommandService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private static final String MANAGE = "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_MANAGE')";

    private final AdminRestaurantUserQueryService queryService;
    private final AdminRestaurantUserCommandService commandService;
    private final CurrentUserProvider currentUser;

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

    @PatchMapping("/{userId}/status")
    @PreAuthorize(MANAGE)
    ResponseEntity<AdminRestaurantUserResponse> updateStatus(@PathVariable UUID restaurantId, @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(commandService.updateStatus(restaurantId, userId, request,
            currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @PostMapping("/{userId}/temporary-password")
    @PreAuthorize(MANAGE)
    ResponseEntity<TemporaryPasswordResponse> temporaryPassword(@PathVariable UUID restaurantId, @PathVariable UUID userId,
            @Valid @RequestBody TemporaryPasswordRequest request, HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(commandService.issueTemporaryPassword(
            restaurantId, userId, request, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }
}
