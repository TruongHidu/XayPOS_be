package com.possaas.modules.subscription.mapper;

import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.AdminPackageResponse;
import com.possaas.modules.subscription.dto.FeatureEntitlementResponse;
import com.possaas.modules.subscription.dto.FeatureResponse;
import com.possaas.modules.subscription.dto.PackageResponse;
import com.possaas.modules.subscription.entity.Feature;
import com.possaas.modules.subscription.entity.PackagePlan;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PackageMapper {
    public PackageResponse toResponse(
        PackagePlan packagePlan,
        List<SubscriptionFeatureSnapshot.FeatureGrant> features
    ) {
        return new PackageResponse(
            packagePlan.getId(),
            packagePlan.getCode(),
            packagePlan.getName(),
            packagePlan.getDescription(),
            packagePlan.getPriceAmount(),
            packagePlan.getCurrencyCode(),
            packagePlan.getBillingCycleMonths(),
            toFeatureEntitlements(features)
        );
    }

    public AdminPackageResponse toAdminResponse(
        PackagePlan packagePlan,
        List<SubscriptionFeatureSnapshot.FeatureGrant> features
    ) {
        return new AdminPackageResponse(
            packagePlan.getId(),
            packagePlan.getCode(),
            packagePlan.getName(),
            packagePlan.getDescription(),
            packagePlan.getPriceAmount(),
            packagePlan.getCurrencyCode(),
            packagePlan.getBillingCycleMonths(),
            packagePlan.isActive(),
            toFeatureEntitlements(features)
        );
    }

    public FeatureResponse toResponse(Feature feature) {
        return new FeatureResponse(
            feature.getId(),
            feature.getCode(),
            feature.getName(),
            feature.getDescription(),
            feature.isActive()
        );
    }

    private List<FeatureEntitlementResponse> toFeatureEntitlements(
        List<SubscriptionFeatureSnapshot.FeatureGrant> features
    ) {
        return features.stream()
            .map(feature -> new FeatureEntitlementResponse(feature.code(), feature.limits()))
            .toList();
    }
}
