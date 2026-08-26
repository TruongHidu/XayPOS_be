package com.possaas.modules.subscription.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "restaurant_subscriptions")
public class RestaurantSubscription extends BaseAuditable {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "auto_renew", nullable = false)
    private boolean autoRenew;

    @Column(name = "price_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)")
    private String currencyCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> featureSnapshot = new HashMap<>();

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    @Column(nullable = false)
    private long version;
}
