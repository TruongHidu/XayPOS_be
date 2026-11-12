package com.possaas.modules.restaurant.controller;

import com.possaas.modules.restaurant.dto.TransferAccountResponse;
import com.possaas.modules.restaurant.dto.UpdateTransferAccountRequest;
import com.possaas.modules.restaurant.service.RestaurantTransferAccountCommandService;
import com.possaas.modules.restaurant.service.RestaurantTransferAccountQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** The account customers transfer to; only someone who may edit the restaurant profile can change it. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/me/transfer-account")
public class RestaurantTransferAccountController {
    private final RestaurantTransferAccountQueryService query;
    private final RestaurantTransferAccountCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_VIEW')")
    public ResponseEntity<TransferAccountResponse> get() {
        return noStore(query.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_UPDATE')")
    public ResponseEntity<TransferAccountResponse> update(@Valid @RequestBody UpdateTransferAccountRequest request,
            HttpServletRequest http) {
        return noStore(command.update(request, http.getRemoteAddr()));
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('RESTAURANT_PROFILE_UPDATE')")
    public ResponseEntity<TransferAccountResponse> clear(HttpServletRequest http) {
        return noStore(command.clear(http.getRemoteAddr()));
    }

    private static ResponseEntity<TransferAccountResponse> noStore(TransferAccountResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
