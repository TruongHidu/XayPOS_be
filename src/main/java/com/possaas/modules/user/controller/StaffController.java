package com.possaas.modules.user.controller;

import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.service.*;
import com.possaas.modules.user.repository.AdminRestaurantUserCriteria;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/staff")
public class StaffController {
    private final StaffQueryService query;
    private final StaffCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('STAFF_VIEW')")
    public PageResponse<StaffResponse> search(@RequestParam(required=false) String q,
        @RequestParam(required=false) String roleCode, @RequestParam(required=false) Boolean active,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
        @RequestParam(defaultValue="createdAt") String sortBy, @RequestParam(defaultValue="desc") String direction) {
        return query.search(AdminRestaurantUserCriteria.from(q, roleCode, active, page, size, sortBy, direction));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('STAFF_VIEW')")
    public List<StaffRoleResponse> roles() { return query.roles(); }

    @GetMapping("/{staffId}")
    @PreAuthorize("hasAuthority('STAFF_VIEW')")
    public StaffResponse detail(@PathVariable UUID staffId) { return query.detail(staffId); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('STAFF_CREATE')")
    public StaffResponse create(@Valid @RequestBody CreateStaffRequest request, HttpServletRequest http) {
        return command.create(request, http.getRemoteAddr());
    }

    @PatchMapping("/{staffId}")
    @PreAuthorize("hasAuthority('STAFF_UPDATE')")
    public StaffResponse update(@PathVariable UUID staffId, @Valid @RequestBody UpdateStaffRequest request, HttpServletRequest http) {
        return command.update(staffId, request, http.getRemoteAddr());
    }

    @PatchMapping("/{staffId}/status")
    @PreAuthorize("hasAuthority('STAFF_DISABLE')")
    public StaffResponse status(@PathVariable UUID staffId, @Valid @RequestBody UpdateStaffStatusRequest request, HttpServletRequest http) {
        return command.status(staffId, request, http.getRemoteAddr());
    }
}
