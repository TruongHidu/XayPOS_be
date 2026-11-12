package com.possaas.modules.restaurant.controller;

import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.restaurant.dto.AdminRestaurantNoteResponse;
import com.possaas.modules.restaurant.dto.CreateRestaurantNoteRequest;
import com.possaas.modules.restaurant.service.AdminRestaurantNoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/admin/restaurants/{restaurantId}/notes")
@RequiredArgsConstructor
public class AdminRestaurantNoteController {
    private final AdminRestaurantNoteService notes;
    private final CurrentUserProvider currentUser;

    @GetMapping
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_VIEW')")
    ResponseEntity<List<AdminRestaurantNoteResponse>> list(@PathVariable UUID restaurantId) {
        return ResponseEntity.ok(notes.list(restaurantId));
    }

    @PostMapping
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_MANAGE')")
    ResponseEntity<AdminRestaurantNoteResponse> add(@PathVariable UUID restaurantId,
            @Valid @RequestBody CreateRestaurantNoteRequest request, HttpServletRequest http) {
        return ResponseEntity.ok(notes.add(restaurantId, request.content(), currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @DeleteMapping("/{noteId}")
    @PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('RESTAURANT_MANAGE')")
    ResponseEntity<Void> delete(@PathVariable UUID restaurantId, @PathVariable UUID noteId, HttpServletRequest http) {
        notes.delete(restaurantId, noteId, currentUser.getRequired().userId(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
