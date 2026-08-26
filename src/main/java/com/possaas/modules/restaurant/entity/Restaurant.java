package com.possaas.modules.restaurant.entity;

import com.possaas.common.persistence.BaseAuditable;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "restaurants")
public class Restaurant extends BaseAuditable {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 50) private String code;
    @Column(nullable = false, length = 150) private String name;
    @Column(name = "legal_name", length = 200) private String legalName;
    @Column(length = 30) private String phone;
    @Column(length = 500) private String address;
    @Column(nullable = false, length = 50) private String timezone = "Asia/Ho_Chi_Minh";
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "currency_code", nullable = false, columnDefinition = "char(3)") private String currencyCode = "VND";
    @Column(name = "public_order_token", unique = true, length = 128) private String publicOrderToken;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RestaurantStatus status = RestaurantStatus.ACTIVE;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, Object> settings = new HashMap<>();
    @Column(name = "deleted_at") private Instant deletedAt;
}
