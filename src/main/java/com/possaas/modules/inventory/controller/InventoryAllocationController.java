package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.InventoryAllocationResponse;
import com.possaas.modules.inventory.service.InventoryAllocationQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/orders/{orderId}/items/{orderItemId}/allocation")
public class InventoryAllocationController {
    private final InventoryAllocationQueryService query;

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('RECIPE_VIEW') and hasAuthority('INVENTORY_VIEW')")
    public ResponseEntity<InventoryAllocationResponse> find(@PathVariable UUID orderId, @PathVariable UUID orderItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.find(orderId, orderItemId));
    }
}
