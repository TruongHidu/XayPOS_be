package com.possaas.modules.subscription.controller;

import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.dto.AdminPackageResponse;
import com.possaas.modules.subscription.dto.CreateFeatureRequest;
import com.possaas.modules.subscription.dto.CreatePackageRequest;
import com.possaas.modules.subscription.dto.FeatureResponse;
import com.possaas.modules.subscription.dto.PackageFeatureRequest;
import com.possaas.modules.subscription.dto.UpdateFeatureRequest;
import com.possaas.modules.subscription.dto.UpdatePackageRequest;
import com.possaas.modules.subscription.service.FeatureAdminService;
import com.possaas.modules.subscription.service.PackageAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminPackageController {
    private final FeatureAdminService featureAdminService;
    private final PackageAdminService packageAdminService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/features")
    @PreAuthorize("hasAuthority('PACKAGE_VIEW')")
    ResponseEntity<List<FeatureResponse>> features(
        @RequestParam(defaultValue = "true") boolean includeInactive
    ) {
        return ResponseEntity.ok(featureAdminService.findAll(includeInactive));
    }

    @PostMapping("/features")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<FeatureResponse> createFeature(
        @Valid @RequestBody CreateFeatureRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.status(HttpStatus.CREATED).body(
            featureAdminService.create(request, actor.userId(), httpRequest.getRemoteAddr())
        );
    }

    @PutMapping("/features/{featureCode}")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<FeatureResponse> updateFeature(
        @PathVariable String featureCode,
        @Valid @RequestBody UpdateFeatureRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(featureAdminService.update(
            featureCode, request, actor.userId(), httpRequest.getRemoteAddr()
        ));
    }

    @GetMapping("/packages")
    @PreAuthorize("hasAuthority('PACKAGE_VIEW')")
    ResponseEntity<List<AdminPackageResponse>> packages(
        @RequestParam(defaultValue = "true") boolean includeInactive
    ) {
        return ResponseEntity.ok(packageAdminService.findAll(includeInactive));
    }

    @PostMapping("/packages")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<AdminPackageResponse> createPackage(
        @Valid @RequestBody CreatePackageRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.status(HttpStatus.CREATED).body(
            packageAdminService.create(request, actor.userId(), httpRequest.getRemoteAddr())
        );
    }

    @PutMapping("/packages/{packageCode}")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<AdminPackageResponse> updatePackage(
        @PathVariable String packageCode,
        @Valid @RequestBody UpdatePackageRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(packageAdminService.update(
            packageCode, request, actor.userId(), httpRequest.getRemoteAddr()
        ));
    }

    @PostMapping("/packages/{packageCode}/features/{featureCode}")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<AdminPackageResponse> addFeature(
        @PathVariable String packageCode,
        @PathVariable String featureCode,
        @Valid @RequestBody PackageFeatureRequest request,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(packageAdminService.addFeature(
            packageCode, featureCode, request, actor.userId(), httpRequest.getRemoteAddr()
        ));
    }

    @DeleteMapping("/packages/{packageCode}/features/{featureCode}")
    @PreAuthorize("hasAuthority('PACKAGE_MANAGE')")
    ResponseEntity<AdminPackageResponse> removeFeature(
        @PathVariable String packageCode,
        @PathVariable String featureCode,
        HttpServletRequest httpRequest
    ) {
        CurrentUser actor = currentUserProvider.getRequired();
        return ResponseEntity.ok(packageAdminService.removeFeature(
            packageCode, featureCode, actor.userId(), httpRequest.getRemoteAddr()
        ));
    }
}
