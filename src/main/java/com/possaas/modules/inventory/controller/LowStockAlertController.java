package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/inventory/alerts")
public class LowStockAlertController {
    private final LowStockAlertQueryService queries;
    private final LowStockAlertCommandService commands;

    @GetMapping @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public ResponseEntity<PageResponse<LowStockAlertResponse>> search(@Valid @ModelAttribute LowStockAlertSearch search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.search(search));
    }
    @GetMapping("/{alertId}") @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public ResponseEntity<LowStockAlertResponse> find(@PathVariable UUID alertId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.find(alertId));
    }
    @PostMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW') and hasAuthority('INVENTORY_ALERT_ACKNOWLEDGE')")
    public ResponseEntity<LowStockAlertResponse> acknowledge(@PathVariable UUID alertId,
            @Valid @RequestBody LowStockAlertRequests.Acknowledge request,HttpServletRequest http) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(commands.acknowledge(alertId,request,http.getRemoteAddr()));
    }
}
