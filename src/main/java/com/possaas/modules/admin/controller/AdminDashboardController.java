package com.possaas.modules.admin.controller;

import com.possaas.modules.admin.dto.AdminDashboardSummaryResponse;
import com.possaas.modules.admin.service.AdminDashboardQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {
    private final AdminDashboardQueryService queryService;

    @GetMapping("/summary")
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) "
            + "and hasAuthority('ADMIN_DASHBOARD_VIEW')"
    )
    ResponseEntity<AdminDashboardSummaryResponse> summary() {
        return ResponseEntity.ok(queryService.getSummary());
    }
}
