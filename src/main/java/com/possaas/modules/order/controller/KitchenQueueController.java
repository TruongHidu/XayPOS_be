package com.possaas.modules.order.controller;

import com.possaas.modules.order.dto.KitchenQueueItemResponse;
import com.possaas.modules.order.dto.KitchenQueueSearch;
import com.possaas.modules.order.service.KitchenQueueQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/kitchen/items")
public class KitchenQueueController {
    private final KitchenQueueQueryService query;
    @GetMapping @PreAuthorize("hasAuthority('KITCHEN_VIEW')")
    public ResponseEntity<PageResponse<KitchenQueueItemResponse>> search(@Valid @ModelAttribute KitchenQueueSearch search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.search(search));
    }
}
