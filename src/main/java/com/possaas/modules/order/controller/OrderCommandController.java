package com.possaas.modules.order.controller;

import com.possaas.modules.order.dto.*;
import com.possaas.modules.order.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/orders")
public class OrderCommandController {
    private final OrderCreationService creation;
    private final OrderCommandService command;
    private final OrderAppendService append;
    @PostMapping @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderRequests.Create request,
            @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest http) {
        var result=creation.create(request,key,http.getRemoteAddr());
        return ResponseEntity.status(result.replay()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(result.replay())).body(result.response());
    }
    @PatchMapping("/{orderId}") @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<OrderResponse> update(@PathVariable UUID orderId,@Valid @RequestBody OrderRequests.Update request,HttpServletRequest http) {
        return ok(command.update(orderId,request,http.getRemoteAddr()));
    }
    @PostMapping("/{orderId}/items") @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<OrderResponse> add(@PathVariable UUID orderId,@Valid @RequestBody OrderRequests.AddItems request,
            @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest http) {
        var result=append.addItems(orderId,request,key,http.getRemoteAddr());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Idempotency-Replayed",Boolean.toString(result.replay())).body(result.response());
    }
    @PatchMapping("/{orderId}/items/{orderItemId}") @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<OrderResponse> updateItem(@PathVariable UUID orderId,@PathVariable UUID orderItemId,
            @Valid @RequestBody OrderRequests.UpdateItem request,HttpServletRequest http) {
        return ok(command.updateItem(orderId,orderItemId,request,http.getRemoteAddr()));
    }
    @PostMapping("/{orderId}/items/{orderItemId}/cancel") @PreAuthorize("hasAuthority('ORDER_UPDATE') or hasAuthority('ORDER_CANCEL')")
    public ResponseEntity<OrderResponse> cancelItem(@PathVariable UUID orderId,@PathVariable UUID orderItemId,
            @Valid @RequestBody OrderRequests.Cancel request,HttpServletRequest http) {
        return ok(command.cancelItem(orderId,orderItemId,request,http.getRemoteAddr()));
    }
    @PostMapping("/{orderId}/confirm") @PreAuthorize("hasAuthority('ORDER_UPDATE')")
    public ResponseEntity<OrderResponse> confirm(@PathVariable UUID orderId,@Valid @RequestBody OrderRequests.Version request,HttpServletRequest http) {
        return ok(command.confirm(orderId,request,http.getRemoteAddr()));
    }
    @PostMapping("/{orderId}/cancel") @PreAuthorize("hasAuthority('ORDER_CANCEL')")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID orderId,@Valid @RequestBody OrderRequests.Cancel request,HttpServletRequest http) {
        return ok(command.cancel(orderId,request,http.getRemoteAddr()));
    }
    private ResponseEntity<OrderResponse> ok(OrderResponse body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
