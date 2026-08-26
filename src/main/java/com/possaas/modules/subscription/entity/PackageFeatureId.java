package com.possaas.modules.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
@Embeddable
public class PackageFeatureId implements Serializable {
    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "feature_id", nullable = false)
    private UUID featureId;

    public PackageFeatureId(UUID packageId, UUID featureId) {
        this.packageId = packageId;
        this.featureId = featureId;
    }
}
