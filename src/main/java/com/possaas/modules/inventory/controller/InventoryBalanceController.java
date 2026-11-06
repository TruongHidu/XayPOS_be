package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.InventoryBalanceRequests;
import com.possaas.modules.inventory.dto.InventoryBalanceResponse;
import com.possaas.modules.inventory.dto.InventoryBalanceSearch;
import com.possaas.modules.inventory.dto.LowStockSearch;
import com.possaas.modules.inventory.service.InventoryBalanceCommandService;
import com.possaas.modules.inventory.service.InventoryBalanceQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/inventory")
public class InventoryBalanceController {
    private final InventoryBalanceQueryService queries;
    private final InventoryBalanceCommandService commands;

    @GetMapping("/warehouses/{warehouseId}/balances")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<InventoryBalanceResponse> list(@PathVariable UUID warehouseId,
            @Valid @ModelAttribute InventoryBalanceSearch search) {
        return queries.list(warehouseId, search);
    }

    @GetMapping("/warehouses/{warehouseId}/balances/{ingredientId}")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public InventoryBalanceResponse detail(@PathVariable UUID warehouseId, @PathVariable UUID ingredientId) {
        return queries.detail(warehouseId, ingredientId);
    }

    @PatchMapping("/warehouses/{warehouseId}/balances/{ingredientId}/threshold")
    @PreAuthorize("hasAuthority('INVENTORY_THRESHOLD_UPDATE')")
    public InventoryBalanceResponse threshold(@PathVariable UUID warehouseId, @PathVariable UUID ingredientId,
            @Valid @RequestBody InventoryBalanceRequests.UpdateThreshold request,
            HttpServletRequest httpRequest) {
        return commands.updateThreshold(warehouseId, ingredientId, request, httpRequest.getRemoteAddr());
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<InventoryBalanceResponse> lowStock(@Valid @ModelAttribute LowStockSearch search) {
        return queries.lowStock(search);
    }
}
