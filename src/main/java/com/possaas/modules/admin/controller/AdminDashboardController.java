package com.possaas.modules.admin.controller;

import com.possaas.modules.admin.dto.AdminDashboardSummaryResponse;
import com.possaas.modules.admin.dto.AdminRevenueResponse;
import com.possaas.modules.admin.service.AdminDashboardQueryService;
import com.possaas.modules.admin.service.AdminRevenueQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardQueryService queryService;
    private final AdminRevenueQueryService revenueService;

    @GetMapping("/summary")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) "
            + "and hasAuthority('ADMIN_DASHBOARD_VIEW')"
    )
    ResponseEntity<AdminDashboardSummaryResponse> summary() {
        return ResponseEntity.ok(queryService.getSummary());
    }

    @GetMapping("/revenue")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) "
            + "and hasAuthority('ADMIN_DASHBOARD_VIEW')"
    )
    ResponseEntity<AdminRevenueResponse> revenue(@RequestParam(defaultValue = "12") @Min(3) @Max(24) int months) {
        return ResponseEntity.ok(revenueService.revenue(months));
    }
}
