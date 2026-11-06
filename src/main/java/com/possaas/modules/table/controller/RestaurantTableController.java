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
@RequestMapping("/api/v1/tables")
public class RestaurantTableController {
    private final RestaurantTableCommandService command;
    private final RestaurantTableQueryService query;
    private final TableSessionCommandService sessionCommand;
    private final TableSessionQueryService sessionQuery;

    @GetMapping @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public PageResponse<RestaurantTableResponse> search(@Valid @ModelAttribute TableSearch.Tables search) {
        return query.search(search);
    }

    @GetMapping("/{tableId}") @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public RestaurantTableResponse detail(@PathVariable UUID tableId) {
        return query.detail(tableId);
    }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('TABLE_CREATE')")
    public RestaurantTableResponse create(@Valid @RequestBody TableRequests.CreateTable request, HttpServletRequest http) {
        return command.create(request, http.getRemoteAddr());
    }

    @PatchMapping("/{tableId}") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public RestaurantTableResponse update(@PathVariable UUID tableId, @Valid @RequestBody TableRequests.UpdateTable request, HttpServletRequest http) {
        return command.update(tableId, request, http.getRemoteAddr());
    }

    @PatchMapping("/{tableId}/status") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public RestaurantTableResponse status(@PathVariable UUID tableId, @Valid @RequestBody TableRequests.Status request, HttpServletRequest http) {
        return command.status(tableId, request, http.getRemoteAddr());
    }

    @PatchMapping("/{tableId}/area") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public RestaurantTableResponse area(@PathVariable UUID tableId, @Valid @RequestBody TableRequests.AreaAssignment request, HttpServletRequest http) {
        return command.area(tableId, request, http.getRemoteAddr());
    }

    @DeleteMapping("/{tableId}") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public void delete(@PathVariable UUID tableId, @RequestParam @Min(0) long expectedVersion, HttpServletRequest http) {
        command.delete(tableId, expectedVersion, http.getRemoteAddr());
    }

    @GetMapping("/{tableId}/qr") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public TableQrResponse qr(@PathVariable UUID tableId) {
        return query.qr(tableId);
    }

    @PostMapping("/{tableId}/qr/rotate") @PreAuthorize("hasAuthority('TABLE_UPDATE')")
    public TableQrResponse rotate(@PathVariable UUID tableId, @Valid @RequestBody TableRequests.Version request, HttpServletRequest http) {
        return command.rotateQr(tableId, request, http.getRemoteAddr());
    }

    @PostMapping("/{tableId}/sessions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasAuthority('TABLE_OPEN')")
    public TableSessionResponse open(@PathVariable UUID tableId, @Valid @RequestBody TableRequests.OpenSession request, HttpServletRequest http) {
        return sessionCommand.open(tableId, request, http.getRemoteAddr());
    }

    @GetMapping("/{tableId}/current-session") @PreAuthorize("hasAuthority('TABLE_VIEW')")
    public ResponseEntity<TableSessionResponse> current(@PathVariable UUID tableId) {
        return sessionQuery.current(tableId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
