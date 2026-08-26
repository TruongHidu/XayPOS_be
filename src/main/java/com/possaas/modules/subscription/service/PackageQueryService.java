package com.possaas.modules.subscription.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.PackageResponse;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.mapper.PackageMapper;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PackageQueryService {
    private final PackagePlanRepository packageRepository;
    private final PackageFeatureCatalog packageFeatureCatalog;
    private final PackageMapper packageMapper;

    @Transactional(readOnly = true)
    public List<PackageResponse> findActivePackages() {
        List<PackagePlan> packages = packageRepository.findAllByActiveTrueOrderByPriceAmountAsc();
        Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> features = packageFeatureCatalog
            .getActiveFeatures(packages.stream().map(PackagePlan::getId).toList());
        return packages.stream()
            .map(packagePlan -> packageMapper.toResponse(
                packagePlan,
                features.getOrDefault(packagePlan.getId(), List.of())
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public PackageResponse findActivePackage(String packageCode) {
        PackagePlan packagePlan = packageRepository.findByCodeAndActiveTrue(normalizeCode(packageCode))
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
        return packageMapper.toResponse(
            packagePlan,
            packageFeatureCatalog.getActiveFeatures(packagePlan.getId())
        );
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
