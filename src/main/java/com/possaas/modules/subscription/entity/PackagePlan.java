package com.possaas.modules.subscription.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "packages")
public class PackagePlan extends BaseAuditable {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "price_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)")
    private String currencyCode;

    @Column(name = "billing_cycle_months", nullable = false)
    private short billingCycleMonths;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
