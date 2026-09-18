package com.possaas.modules.audit.controller;

import com.possaas.modules.audit.dto.AuditLogPageResponse;
import com.possaas.modules.audit.query.AuditLogCriteria;
import com.possaas.modules.audit.query.AuditScope;
import com.possaas.modules.audit.service.AuditQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {
    private final AuditQueryService auditQueryService;

    @GetMapping
    @PreAuthorize(
        "@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('AUDIT_VIEW')"
    )
    ResponseEntity<AuditLogPageResponse> search(
        @RequestParam(defaultValue = "ALL") AuditScope scope,
        @RequestParam(required = false) UUID restaurantId,
        @RequestParam(required = false) UUID actorUserId,
        @RequestParam(required = false) @Size(max = 100) String actionCode,
        @RequestParam(required = false) @Size(max = 80) String entityType,
        @RequestParam(required = false) UUID entityId,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        AuditLogCriteria criteria = new AuditLogCriteria(
            scope,
            restaurantId,
            actorUserId,
            actionCode,
            entityType,
            entityId,
            from,
            to
        );
        return ResponseEntity.ok(auditQueryService.search(criteria, page, size));
    }
}
