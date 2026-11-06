package com.possaas.modules.order.controller;

import com.possaas.modules.order.dto.OrderRecipeSnapshotResponse;
import com.possaas.modules.order.service.OrderRecipeSnapshotQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{orderId}/items/{orderItemId}/recipe-snapshot")
public class OrderRecipeSnapshotController {
    private final OrderRecipeSnapshotQueryService query;

    @GetMapping
    @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('RECIPE_VIEW')")
    public ResponseEntity<OrderRecipeSnapshotResponse> find(@PathVariable UUID orderId, @PathVariable UUID orderItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.find(orderId, orderItemId));
    }
}
