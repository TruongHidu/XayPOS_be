package com.possaas.modules.order.controller;

import com.possaas.modules.order.dto.OrderResponse;
import com.possaas.modules.order.service.OrderQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/table-sessions")
public class OrderSessionQueryController {
    private final OrderQueryService query;
    @GetMapping("/{sessionId}/active-order") @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ResponseEntity<OrderResponse> active(@PathVariable UUID sessionId) {
        var result=query.activeOrder(sessionId);
        return result.map(order -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(order))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }
}
