package com.possaas.modules.order.controller;

import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.service.OrderQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/orders")
@PreAuthorize("hasAuthority('ORDER_VIEW')")
public class OrderQueryController {
    private final OrderQueryService query;
    @GetMapping
    public ResponseEntity<PageResponse<OrderSummaryResponse>> search(@Valid @ModelAttribute OrderSearch request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.search(request));
    }
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> detail(@PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.detail(orderId));
    }
}
