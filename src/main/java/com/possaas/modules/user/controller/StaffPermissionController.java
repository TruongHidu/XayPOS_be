package com.possaas.modules.user.controller;
import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/staff")
@PreAuthorize("hasAuthority('STAFF_PERMISSION_MANAGE')")
public class StaffPermissionController {
    private final StaffPermissionQueryService query;
    private final StaffPermissionCommandService command;
    @GetMapping("/permissions")
    public List<StaffPermissionCatalogResponse> catalog() { return query.catalog(); }
    @GetMapping("/{staffId}/permissions")
    public StaffPermissionsResponse get(@PathVariable UUID staffId) { return query.get(staffId); }
    @PutMapping("/{staffId}/permissions")
    public StaffPermissionsResponse replace(@PathVariable UUID staffId, @Valid @RequestBody ReplaceStaffPermissionsRequest request, HttpServletRequest http) {
        return command.replace(staffId, request, http.getRemoteAddr());
    }
}
