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
@RequestMapping("/api/v1/menu/groups")
public class MenuGroupController {
    private final MenuGroupQueryService query;
    private final MenuGroupCommandService command;

    @GetMapping
    @PreAuthorize("hasAuthority('MENU_VIEW')")
    public PageResponse<MenuGroupResponse> search(@Valid @ModelAttribute MenuSearch search) {
        return query.search(search);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MENU_VIEW')")
    public MenuGroupResponse detail(@PathVariable UUID id) {
        return query.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('MENU_CREATE')")
    public MenuGroupResponse create(@Valid @RequestBody MenuRequests.CreateGroup request, HttpServletRequest http) {
        return command.create(request, http.getRemoteAddr());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuGroupResponse update(@PathVariable UUID id, @Valid @RequestBody MenuRequests.UpdateGroup request,
            HttpServletRequest http) {
        return command.update(id, request, http.getRemoteAddr());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('MENU_UPDATE')")
    public MenuGroupResponse status(@PathVariable UUID id, @Valid @RequestBody MenuRequests.Status request,
            HttpServletRequest http) {
        return command.status(id, request, http.getRemoteAddr());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('MENU_DELETE')")
    public void delete(@PathVariable UUID id, @RequestParam @Min(0) long expectedVersion, HttpServletRequest http) {
        command.delete(id, expectedVersion, http.getRemoteAddr());
    }

}
