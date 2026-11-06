package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.WarehouseRequests;
import com.possaas.modules.inventory.dto.WarehouseResponse;
import com.possaas.modules.inventory.dto.WarehouseSearch;
import com.possaas.modules.inventory.service.WarehouseCommandService;
import com.possaas.modules.inventory.service.WarehouseQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/inventory/warehouses")
public class WarehouseController {
    private final WarehouseQueryService queries;
    private final WarehouseCommandService commands;

    @GetMapping
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<WarehouseResponse> search(@Valid @ModelAttribute WarehouseSearch search) {
        return queries.search(search);
    }

    @GetMapping("/{warehouseId}")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public WarehouseResponse detail(@PathVariable UUID warehouseId) {
        return queries.detail(warehouseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('WAREHOUSE_CREATE')")
    public WarehouseResponse create(@Valid @RequestBody WarehouseRequests.Create request,
            HttpServletRequest httpRequest) {
        return commands.create(request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/{warehouseId}")
    @PreAuthorize("hasAuthority('WAREHOUSE_UPDATE')")
    public WarehouseResponse update(@PathVariable UUID warehouseId,
            @Valid @RequestBody WarehouseRequests.Update request, HttpServletRequest httpRequest) {
        return commands.update(warehouseId, request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/{warehouseId}/status")
    @PreAuthorize("hasAuthority('WAREHOUSE_UPDATE')")
    public WarehouseResponse status(@PathVariable UUID warehouseId,
            @Valid @RequestBody WarehouseRequests.Status request, HttpServletRequest httpRequest) {
        return commands.status(warehouseId, request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/{warehouseId}/make-default")
    @PreAuthorize("hasAuthority('WAREHOUSE_UPDATE')")
    public WarehouseResponse makeDefault(@PathVariable UUID warehouseId,
            @Valid @RequestBody WarehouseRequests.MakeDefault request, HttpServletRequest httpRequest) {
        return commands.makeDefault(warehouseId, request, httpRequest.getRemoteAddr());
    }

    @DeleteMapping("/{warehouseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('WAREHOUSE_DELETE')")
    public void delete(@PathVariable UUID warehouseId, @RequestParam @Min(0) long expectedVersion,
            HttpServletRequest httpRequest) {
        commands.delete(warehouseId, expectedVersion, httpRequest.getRemoteAddr());
    }
}
