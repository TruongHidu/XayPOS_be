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
@RequestMapping("/api/v1/table-areas")
public class TableAreaController {
    private final TableAreaCommandService command;
    private final TableAreaQueryService query;

    @GetMapping @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public PageResponse<TableAreaResponse> search(@Valid @ModelAttribute TableSearch.Area search) {
        return query.search(search);
    }

    @GetMapping("/{areaId}") @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public TableAreaResponse detail(@PathVariable UUID areaId) {
        return query.detail(areaId);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('TABLE_CREATE')")
    public TableAreaResponse create(@Valid @RequestBody TableRequests.CreateArea request, HttpServletRequest http) {
        return command.create(request, http.getRemoteAddr());
    }

    @PatchMapping("/{areaId}") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public TableAreaResponse update(@PathVariable UUID areaId, @Valid @RequestBody TableRequests.UpdateArea request, HttpServletRequest http) {
        return command.update(areaId, request, http.getRemoteAddr());
    }

    @PatchMapping("/{areaId}/status") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public TableAreaResponse status(@PathVariable UUID areaId, @Valid @RequestBody TableRequests.AreaStatus request, HttpServletRequest http) {
        return command.status(areaId, request, http.getRemoteAddr());
    }

    @DeleteMapping("/{areaId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public void delete(@PathVariable UUID areaId, @RequestParam @Min(0) long expectedVersion, HttpServletRequest http) {
        command.delete(areaId, expectedVersion, http.getRemoteAddr());
    }
}
