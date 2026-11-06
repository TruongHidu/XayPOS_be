package com.possaas.modules.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubscriptionFeatureSnapshotTest {
    @Test
    void v2RoundTripPreservesPackageLimitAndExplicitUnlimited() {
        for (Long limit : java.util.Arrays.asList(5L, null)) {
            var snapshot = new SubscriptionFeatureSnapshot(2, "PRO", List.of(), CAPTURED_AT, limit);
            assertThat(snapshot.toMap()).containsEntry("maxStaff", limit);
            var restored = SubscriptionFeatureSnapshot.fromMap(snapshot.toMap());
            assertThat(restored).isEqualTo(snapshot);
            assertThat(restored.effectiveMaxStaff()).isEqualTo(limit);
        }
    }

    @Test
    void legacySnapshotKeepsItsOwnLimitAndV2NeverFallsBackToLegacyLimit() {
        var grants = List.of(new SubscriptionFeatureSnapshot.FeatureGrant("STAFF_MANAGEMENT", Map.of("maxStaff", 7)));
        var legacy = new SubscriptionFeatureSnapshot(1, "PRO", grants, CAPTURED_AT);
        assertThat(SubscriptionFeatureSnapshot.fromMap(legacy.toMap()).effectiveMaxStaff()).isEqualTo(7L);
        assertThat(new SubscriptionFeatureSnapshot(2, "PRO", grants, CAPTURED_AT, null).effectiveMaxStaff()).isNull();
    }

    @Test
    void malformedV2LimitFailsClosed() {
        for (Object value : List.of("3", -1, 0, 1.5, true)) {
            var map = new HashMap<String, Object>();
            map.put("schemaVersion", 2);
            map.put("maxStaff", value);
            assertThatThrownBy(() -> SubscriptionFeatureSnapshot.fromMap(map))
                .isInstanceOf(com.possaas.common.exception.BusinessException.class);
        }
        assertThatThrownBy(() -> SubscriptionFeatureSnapshot.fromMap(Map.of("schemaVersion", 2)))
            .isInstanceOf(com.possaas.common.exception.BusinessException.class);
    }

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
