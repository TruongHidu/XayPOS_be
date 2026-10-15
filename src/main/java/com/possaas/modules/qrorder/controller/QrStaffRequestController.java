package com.possaas.modules.qrorder.controller;

import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/qr-order-requests")
public class QrStaffRequestController {
    private final QrStaffQueryService query;
    private final QrStaffDecisionService decision;
    @GetMapping @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ResponseEntity<PageResponse<QrStaffRequestSummary>> search(@Valid @ModelAttribute QrStaffSearch filter) { return ok(query.search(filter)); }
    @GetMapping("/{requestId}") @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ResponseEntity<QrStaffRequestDetail> detail(@PathVariable UUID requestId) { return ok(query.detail(requestId)); }
    @PostMapping("/{requestId}/approve") @PreAuthorize("hasAuthority('ORDER_CREATE') and hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<QrApprovalResponse> approve(@PathVariable UUID requestId, @Valid @RequestBody QrStaffRequests.Approve body,
            HttpServletRequest http) { return ok(decision.approve(requestId, body, http.getRemoteAddr())); }
    @PostMapping("/{requestId}/reject") @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<QrStaffRequestSummary> reject(@PathVariable UUID requestId, @Valid @RequestBody QrStaffRequests.Reject body,
            HttpServletRequest http) { return ok(decision.reject(requestId, body, http.getRemoteAddr())); }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
