package com.possaas.modules.subscription.service;

import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.PackagePlan;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeatureSnapshotFactory {
    private final PackageFeatureCatalog packageFeatureCatalog;
    private final Clock clock;

    public SubscriptionFeatureSnapshot capture(PackagePlan packagePlan) {
        return new SubscriptionFeatureSnapshot(
            1,
            packagePlan.getCode(),
            packageFeatureCatalog.getActiveFeatures(packagePlan.getId()),
            clock.instant()
        );
    }
}
