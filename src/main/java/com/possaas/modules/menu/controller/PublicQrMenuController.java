package com.possaas.modules.menu.controller;

import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.service.PublicQrMenuQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/public/qr-menu")
public class PublicQrMenuController {
    private final PublicQrMenuQueryService query;

    @GetMapping("/{qrToken}")
    public ResponseEntity<PublicQrMenuResponse> context(@PathVariable String qrToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.context(qrToken));
    }

    @GetMapping("/{qrToken}/items")
    public ResponseEntity<PageResponse<PublicMenuItemResponse>> items(@PathVariable String qrToken,
            @Valid @ModelAttribute PublicMenuSearch search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.items(qrToken, search));
    }
}
