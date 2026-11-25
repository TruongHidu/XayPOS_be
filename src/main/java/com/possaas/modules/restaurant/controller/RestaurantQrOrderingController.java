package com.possaas.modules.restaurant.controller;

import com.possaas.modules.restaurant.dto.QrOrderingSettingsResponse;
import com.possaas.modules.restaurant.dto.UpdateQrOrderingSettingsRequest;
import com.possaas.modules.restaurant.service.RestaurantQrOrderingCommandService;
import com.possaas.modules.restaurant.service.RestaurantQrOrderingQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The owner's on/off switches for guest QR ordering at tables and for takeaway. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/me/qr-ordering")
public class RestaurantQrOrderingController {
    private final RestaurantQrOrderingQueryService query;
    private final RestaurantQrOrderingCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_VIEW')")
    public ResponseEntity<QrOrderingSettingsResponse> get() {
        return noStore(query.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_UPDATE')")
    public ResponseEntity<QrOrderingSettingsResponse> update(@Valid @RequestBody UpdateQrOrderingSettingsRequest request,
            HttpServletRequest http) {
        return noStore(command.update(request, http.getRemoteAddr()));
    }

    private static ResponseEntity<QrOrderingSettingsResponse> noStore(QrOrderingSettingsResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
