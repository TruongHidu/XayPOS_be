package com.possaas.modules.menu.controller;

import com.possaas.infrastructure.security.PublicMenuRequests;
import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.service.PublicRestaurantMenuQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(PublicMenuRequests.RESTAURANT_BASE_PATH)
public class PublicRestaurantMenuController {
    private final PublicRestaurantMenuQueryService query;

    @GetMapping("/{menuToken}")
    public ResponseEntity<PublicRestaurantMenuResponse> context(@PathVariable String menuToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.context(menuToken));
    }

    @GetMapping("/{menuToken}/items")
    public ResponseEntity<PageResponse<PublicMenuItemResponse>> items(@PathVariable String menuToken,
            @Valid @ModelAttribute PublicMenuSearch search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.items(menuToken, search));
    }
}
