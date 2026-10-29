package com.possaas.modules.order.controller;
import com.possaas.modules.order.dto.OrderCostSnapshotResponse;
import com.possaas.modules.order.service.OrderCostSnapshotQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{orderId}/items/{orderItemId}/cost-snapshot")
public class OrderCostSnapshotController {
    private final OrderCostSnapshotQueryService query;
    @GetMapping @PreAuthorize("hasAuthority('ORDER_VIEW') and hasAuthority('REPORT_DETAIL_VIEW')")
    public ResponseEntity<OrderCostSnapshotResponse> find(@PathVariable UUID orderId,@PathVariable UUID orderItemId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.find(orderId,orderItemId));
    }
}
