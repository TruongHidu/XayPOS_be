package com.possaas.modules.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.PackagePlan;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureSnapshotFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void capturesPackageCodeFeaturesLimitsAndTimeUsingInjectedClock() {
        PackageFeatureCatalog catalog = mock(PackageFeatureCatalog.class);
        PackagePlan packagePlan = new PackagePlan();
        packagePlan.setId(UUID.randomUUID());
        packagePlan.setCode("PRO");
        when(catalog.getActiveFeatures(packagePlan.getId())).thenReturn(List.of(
            new SubscriptionFeatureSnapshot.FeatureGrant(
                "STAFF_MANAGEMENT",
                Map.of("maxStaff", 50)
            )
        ));
        FeatureSnapshotFactory factory = new FeatureSnapshotFactory(
            catalog,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );

        SubscriptionFeatureSnapshot snapshot = factory.capture(packagePlan);

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.packageCode()).isEqualTo("PRO");
        assertThat(snapshot.capturedAt()).isEqualTo(NOW);
        assertThat(snapshot.features()).containsExactly(
            new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_MANAGEMENT", Map.of("maxStaff", 50))
        );
    }
}
