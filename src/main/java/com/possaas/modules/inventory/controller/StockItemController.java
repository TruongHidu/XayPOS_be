package com.possaas.modules.inventory.controller;
import com.possaas.modules.inventory.service.StockItemQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
@RestController @Validated @RequiredArgsConstructor @RequestMapping("/api/v1/inventory/stock-items")
public class StockItemController {
    private final StockItemQueryService queries;
    @GetMapping @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public ResponseEntity<PageResponse<StockItemQueryService.StockItem>> search(
        @RequestParam(required=false) @Size(max=100) String q,
        @RequestParam(required=false) String itemType,
        @RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(queries.search(q,itemType,page,size));
    }
}
