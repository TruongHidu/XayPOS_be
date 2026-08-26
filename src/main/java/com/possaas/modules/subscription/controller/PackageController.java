package com.possaas.modules.subscription.controller;

import com.possaas.modules.subscription.dto.PackageResponse;
import com.possaas.modules.subscription.service.PackageQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/packages")
@RequiredArgsConstructor
public class PackageController {
    private final PackageQueryService packageQueryService;

    @GetMapping
    ResponseEntity<List<PackageResponse>> findAll() {
        return ResponseEntity.ok(packageQueryService.findActivePackages());
    }

    @GetMapping("/{packageCode}")
    ResponseEntity<PackageResponse> findByCode(@PathVariable String packageCode) {
        return ResponseEntity.ok(packageQueryService.findActivePackage(packageCode));
    }
}
