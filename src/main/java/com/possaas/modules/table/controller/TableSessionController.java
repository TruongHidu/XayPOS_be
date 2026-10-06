package com.possaas.modules.table.controller;

import com.possaas.modules.table.dto.*;
import com.possaas.modules.table.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
}
