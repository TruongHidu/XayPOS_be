package com.possaas.modules.table.controller;

import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/table-sessions")
public class TableSessionController {
    private final TableSessionCommandService command;
    private final TableSessionQueryService query;
    private final TableSessionTransferService transfer;
    private final TableSessionTransferQueryService transfers;

    @GetMapping @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public PageResponse<TableSessionResponse> search(@Valid @ModelAttribute TableSearch.Sessions search) {
        return query.search(search);
    }

    @GetMapping("/{sessionId}") @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public TableSessionResponse detail(@PathVariable UUID sessionId) {
        return query.detail(sessionId);
    }

    @PatchMapping("/{sessionId}") @PreAuthorize("hasAuthority('TABLE_OPEN')")
    public TableSessionResponse update(@PathVariable UUID sessionId, @Valid @RequestBody TableRequests.UpdateSession request, HttpServletRequest http) {
        return command.update(sessionId, request, http.getRemoteAddr());
    }

    @PostMapping("/{sessionId}/cancel") @PreAuthorize("hasAuthority('TABLE_CLOSE')")
    public TableSessionResponse cancel(@PathVariable UUID sessionId, @Valid @RequestBody TableRequests.CancelSession request, HttpServletRequest http) {
        return command.cancel(sessionId, request, http.getRemoteAddr());
    }

    @PostMapping("/{sessionId}/transfer") @PreAuthorize("hasAuthority('TABLE_TRANSFER')")
    public ResponseEntity<TableSessionResponse> transfer(@PathVariable UUID sessionId,
            @Valid @RequestBody TableRequests.TransferSession request,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, HttpServletRequest http) {
        var result = transfer.transfer(sessionId, request, key, http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed", Boolean.toString(result.replay())).body(result.response());
    }

    @GetMapping("/{sessionId}/transfers") @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public ResponseEntity<PageResponse<TableSessionTransferResponse>> transfers(@PathVariable UUID sessionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transfers.history(sessionId, page, size));
    }
}
