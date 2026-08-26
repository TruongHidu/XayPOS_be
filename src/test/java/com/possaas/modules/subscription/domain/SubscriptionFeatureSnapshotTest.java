package com.possaas.modules.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubscriptionFeatureSnapshotTest {
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void detectsPresentAndMissingFeatures() {
        SubscriptionFeatureSnapshot snapshot = new SubscriptionFeatureSnapshot(
            1,
            "PRO",
            List.of(new SubscriptionFeatureSnapshot.FeatureGrant("TABLE_MANAGEMENT", Map.of())),
            CAPTURED_AT
        );

        assertThat(snapshot.contains("TABLE_MANAGEMENT")).isTrue();
        assertThat(snapshot.contains("INVENTORY_MANAGEMENT")).isFalse();
    }

    @Test
    void roundTripPreservesPackageFeaturesLimitsAndVersion() {
        SubscriptionFeatureSnapshot original = new SubscriptionFeatureSnapshot(
            1,
            "PREMIUM",
            List.of(new SubscriptionFeatureSnapshot.FeatureGrant(
                "STAFF_MANAGEMENT",
                Map.of("maxStaff", 50)
            )),
            CAPTURED_AT
        );

        SubscriptionFeatureSnapshot restored = SubscriptionFeatureSnapshot.fromMap(original.toMap());

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void snapshotDoesNotChangeWhenSourceCollectionsChange() {
        Map<String, Object> limits = new HashMap<>();
        limits.put("maxTables", 20);
        List<SubscriptionFeatureSnapshot.FeatureGrant> source = new ArrayList<>();
        source.add(new SubscriptionFeatureSnapshot.FeatureGrant("TABLE_MANAGEMENT", limits));

        SubscriptionFeatureSnapshot snapshot = new SubscriptionFeatureSnapshot(1, "PRO", source, CAPTURED_AT);
        source.clear();
        limits.put("maxTables", 100);

        assertThat(snapshot.features()).hasSize(1);
        assertThat(snapshot.features().getFirst().limits()).containsEntry("maxTables", 20);
    }
}
