package com.possaas.modules.menu.controller;

import com.possaas.modules.menu.dto.*;
import com.possaas.modules.menu.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/menu/items")
public class MenuItemController {
    private final MenuItemQueryService query;
    private final MenuItemCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('MENU_VIEW')")
    public PageResponse<MenuItemResponse> search(@Valid @ModelAttribute MenuSearch search) {
        return query.search(search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MENU_VIEW')")
    public MenuItemResponse detail(@PathVariable UUID id) {
        return query.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MENU_CREATE')")
    public MenuItemResponse create(@Valid @RequestBody MenuRequests.CreateItem request, HttpServletRequest http) {
        return command.create(request, http.getRemoteAddr());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuItemResponse update(@PathVariable UUID id, @Valid @RequestBody MenuRequests.UpdateItem request,
            HttpServletRequest http) {
        return command.update(id, request, http.getRemoteAddr());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuItemResponse status(@PathVariable UUID id, @Valid @RequestBody MenuRequests.Status request,
            HttpServletRequest http) {
        return command.status(id, request, http.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MENU_DELETE')")
    public void delete(@PathVariable UUID id, @RequestParam @Min(0) long expectedVersion, HttpServletRequest http) {
        command.delete(id, expectedVersion, http.getRemoteAddr());
    }

    @PatchMapping("/{id}/availability")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuItemResponse availability(@PathVariable UUID id, @Valid @RequestBody MenuRequests.Availability request,
            HttpServletRequest http) {
        return command.availability(id, request, http.getRemoteAddr());
    }

    @PutMapping("/{id}/group")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuItemResponse group(@PathVariable UUID id, @Valid @RequestBody MenuRequests.GroupAssignment request,
            HttpServletRequest http) {
        return command.group(id, request, http.getRemoteAddr());
    }

}
