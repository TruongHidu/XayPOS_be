package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.AdminPackageResponse;
import com.possaas.modules.subscription.dto.CreatePackageRequest;
import com.possaas.modules.subscription.dto.PackageFeatureRequest;
import com.possaas.modules.subscription.dto.PackageFeatureSelectionRequest;
import com.possaas.modules.subscription.dto.UpdatePackageRequest;
import com.possaas.modules.subscription.entity.Feature;
import com.possaas.modules.subscription.entity.PackageFeature;
import com.possaas.modules.subscription.entity.PackageFeatureId;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.mapper.PackageMapper;
import com.possaas.modules.subscription.repository.FeatureRepository;
import com.possaas.modules.subscription.repository.PackageFeatureRepository;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PackageAdminService {
    private final PackagePlanRepository packageRepository;
    private final FeatureRepository featureRepository;
    private final PackageFeatureRepository packageFeatureRepository;
    private final PackageFeatureCatalog packageFeatureCatalog;
    private final PackageMapper packageMapper;
    private final AuditService auditService;
    private final PackageFeatureAssignmentService assignments;
    private final PackageLimitPolicy limits;

    @Transactional(readOnly = true)
    public List<AdminPackageResponse> findAll(boolean includeInactive) {
        List<PackagePlan> packages = includeInactive
            ? packageRepository.findAllByOrderByPriceAmountAsc()
            : packageRepository.findAllByActiveTrueOrderByPriceAmountAsc();
        Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> features = packageFeatureCatalog
            .getAllFeatures(packages.stream().map(PackagePlan::getId).toList());
        return packages.stream()
            .map(packagePlan -> packageMapper.toAdminResponse(
                packagePlan,
                features.getOrDefault(packagePlan.getId(), List.of())
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public AdminPackageResponse findByCode(String packageCode) {
        PackagePlan packagePlan = requirePackage(packageCode);
        return responseWithAllFeatures(packagePlan);
    }

    @Transactional
    public AdminPackageResponse create(CreatePackageRequest request, UUID actorUserId, String ipAddress) {
        String code = normalizeCode(request.code());
        if (packageRepository.findByCode(code).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PACKAGE_ALREADY_EXISTS", "Package already exists");
        }
        var selected = assignments.prepare(request.features());
        Long maxStaff = limits.validateMaxStaff(request.maxStaff());
        PackagePlan packagePlan = new PackagePlan();
        packagePlan.setCode(code);
        packagePlan.setMaxStaff(maxStaff);
        apply(packagePlan, request.name(), request.description(), request.priceAmount(),
            request.currencyCode(), request.billingCycleMonths(), true);
        packagePlan = packageRepository.saveAndFlush(packagePlan);
        assignments.replace(packagePlan.getId(), selected);
        auditService.record(null, actorUserId, "PACKAGE_CREATED", "packages", packagePlan.getId(),
            null, completeState(packagePlan), ipAddress);
        return responseWithAllFeatures(packagePlan);
    }

    @Transactional
    public AdminPackageResponse update(
        String packageCode,
        UpdatePackageRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        PackagePlan packagePlan = requirePackageForUpdate(packageCode);
        Map<String, Object> before = completeState(packagePlan);
        Map<String, Object> metadataBefore = packageState(packagePlan);
        Long maxStaff = limits.validateMaxStaff(request.maxStaff());
        boolean featuresChanged = request.features() != null
            && assignments.replace(packagePlan.getId(), assignments.prepare(request.features()));
        apply(packagePlan, request.name(), request.description(), request.priceAmount(),
            request.currencyCode(), request.billingCycleMonths(), request.active());
        packagePlan.setMaxStaff(maxStaff);
        if (featuresChanged || !metadataBefore.equals(packageState(packagePlan))) {
            packagePlan = packageRepository.saveAndFlush(packagePlan);
            auditService.record(null, actorUserId, "PACKAGE_UPDATED", "packages", packagePlan.getId(),
                before, completeState(packagePlan), ipAddress);
        }
        return responseWithAllFeatures(packagePlan);
    }

    @Transactional
    public AdminPackageResponse addFeature(
        String packageCode,
        String featureCode,
        PackageFeatureRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        PackagePlan packagePlan = requirePackageForUpdate(packageCode);
        var selected = assignments.prepare(List.of(new PackageFeatureSelectionRequest(featureCode, request.limits()))).getFirst();
        Feature feature = selected.feature();
        PackageFeatureId id = new PackageFeatureId(packagePlan.getId(), feature.getId());
        if (packageFeatureRepository.existsById(id)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "PACKAGE_FEATURE_ALREADY_EXISTS",
                "Feature is already assigned to this package"
            );
        }
        PackageFeature mapping = new PackageFeature();
        mapping.setId(id);
        mapping.setLimits(selected.limits());
        packageFeatureRepository.saveAndFlush(mapping);
        auditService.record(null, actorUserId, "PACKAGE_FEATURE_ADDED", "package_features",
            packagePlan.getId(), null, mappingState(packagePlan, feature, selected.limits()), ipAddress);
        return responseWithAllFeatures(packagePlan);
    }

    @Transactional
    public AdminPackageResponse removeFeature(
        String packageCode,
        String featureCode,
        UUID actorUserId,
        String ipAddress
    ) {
        PackagePlan packagePlan = requirePackageForUpdate(packageCode);
        Feature feature = featureRepository.findByCode(normalizeCode(featureCode))
            .orElseThrow(() -> new ResourceNotFoundException("FEATURE_NOT_FOUND", "Feature not found"));
        PackageFeatureId id = new PackageFeatureId(packagePlan.getId(), feature.getId());
        PackageFeature mapping = packageFeatureRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                "PACKAGE_FEATURE_NOT_FOUND",
                "Feature is not assigned to this package"
            ));
        Map<String, Object> before = mappingState(packagePlan, feature, mapping.getLimits());
        packageFeatureRepository.delete(mapping);
        packageFeatureRepository.flush();
        auditService.record(null, actorUserId, "PACKAGE_FEATURE_REMOVED", "package_features",
            packagePlan.getId(), before, null, ipAddress);
        return responseWithAllFeatures(packagePlan);
    }

    private AdminPackageResponse responseWithAllFeatures(PackagePlan packagePlan) {
        return packageMapper.toAdminResponse(
            packagePlan,
            packageFeatureCatalog.getAllFeatures(List.of(packagePlan.getId()))
                .getOrDefault(packagePlan.getId(), List.of())
        );
    }

    private PackagePlan requirePackage(String packageCode) {
        return packageRepository.findByCode(normalizeCode(packageCode))
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
    }

    private PackagePlan requirePackageForUpdate(String packageCode) {
        return packageRepository.findByCodeForUpdate(normalizeCode(packageCode))
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Package not found"));
    }

    private Map<String, Object> completeState(PackagePlan packagePlan) {
        var state = packageState(packagePlan);
        state.put("features", responseWithAllFeatures(packagePlan).features().stream()
            .map(feature -> Map.of("code", feature.code(), "limits", feature.limits())).toList());
        return state;
    }

    private static void apply(
        PackagePlan packagePlan,
        String name,
        String description,
        java.math.BigDecimal priceAmount,
        String currencyCode,
        short billingCycleMonths,
        boolean active
    ) {
        packagePlan.setName(name.trim());
        packagePlan.setDescription(description);
        packagePlan.setPriceAmount(priceAmount);
        packagePlan.setCurrencyCode(currencyCode.trim().toUpperCase(Locale.ROOT));
        packagePlan.setBillingCycleMonths(billingCycleMonths);
        packagePlan.setActive(active);
    }

    private static Map<String, Object> packageState(PackagePlan packagePlan) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("code", packagePlan.getCode());
        state.put("name", packagePlan.getName());
        state.put("description", packagePlan.getDescription());
        state.put("priceAmount", packagePlan.getPriceAmount().stripTrailingZeros());
        state.put("currencyCode", packagePlan.getCurrencyCode());
        state.put("billingCycleMonths", packagePlan.getBillingCycleMonths());
        state.put("active", packagePlan.isActive());
        state.put("maxStaff", packagePlan.getMaxStaff());
        return state;
    }

    private static Map<String, Object> mappingState(
        PackagePlan packagePlan,
        Feature feature,
        Map<String, Object> limits
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("packageCode", packagePlan.getCode());
        state.put("featureCode", feature.getCode());
        state.put("limits", limits);
        return state;
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
