package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequiredArgsConstructor @RequestMapping("/api/v1/inventory/audits")
public class StockAuditController {
    private static final String VIEW="hasAuthority('INVENTORY_VIEW')";
    private static final String MANAGE=VIEW+" and hasAuthority('STOCK_AUDIT_MANAGE')";
    private static final String CONFIRM=VIEW+" and hasAuthority('STOCK_AUDIT_CONFIRM')";
    private final StockAuditQueryService queries;
    private final StockAuditCommandService commands;
    @GetMapping @PreAuthorize(VIEW)
    public PageResponse<StockAuditSummaryResponse> search(@Valid @ModelAttribute StockAuditSearch search){return queries.search(search);}
    @GetMapping("/{auditId}") @PreAuthorize(VIEW)
    public StockAuditDetailResponse detail(@PathVariable UUID auditId){return queries.detail(auditId);}
    @GetMapping("/{auditId}/items") @PreAuthorize(VIEW)
    public PageResponse<StockAuditItemResponse> items(@PathVariable UUID auditId,@Valid @ModelAttribute StockAuditItemSearch search){return queries.items(auditId,search);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(MANAGE)
    public StockAuditDetailResponse create(@RequestHeader(name="Idempotency-Key",required=false) @Size(max=100) String key,
            @Valid @RequestBody StockAuditRequests.Create request,HttpServletRequest http){return commands.create(key,request,http.getRemoteAddr());}
    @PatchMapping("/{auditId}") @PreAuthorize(MANAGE)
    public StockAuditDetailResponse update(@PathVariable UUID auditId,@Valid @RequestBody StockAuditRequests.Note request,HttpServletRequest http){return commands.update(auditId,request,http.getRemoteAddr());}
    @PostMapping("/{auditId}/start") @PreAuthorize(MANAGE)
    public StockAuditDetailResponse start(@PathVariable UUID auditId,@Valid @RequestBody StockAuditRequests.Transition request,HttpServletRequest http){return commands.start(auditId,request,http.getRemoteAddr());}
    @PatchMapping("/{auditId}/items") @PreAuthorize(MANAGE)
    public StockAuditDetailResponse count(@PathVariable UUID auditId,@Valid @RequestBody StockAuditRequests.Counts request,HttpServletRequest http){return commands.count(auditId,request,http.getRemoteAddr());}
    @PostMapping("/{auditId}/confirm") @PreAuthorize(CONFIRM)
    public StockAuditDetailResponse confirm(@PathVariable UUID auditId,@Valid @RequestBody StockAuditRequests.Transition request,HttpServletRequest http){return commands.confirm(auditId,request,http.getRemoteAddr());}
    @PostMapping("/{auditId}/cancel") @PreAuthorize(MANAGE)
    public StockAuditDetailResponse cancel(@PathVariable UUID auditId,@Valid @RequestBody StockAuditRequests.Note request,HttpServletRequest http){return commands.cancel(auditId,request,http.getRemoteAddr());}
}
