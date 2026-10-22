package com.possaas.modules.inventory.entity;

import com.possaas.modules.inventory.application.port.TrackedIngredientCatalog.TrackedIngredientSnapshot;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity @Table(name="stock_audit_items") @Getter @NoArgsConstructor
public class StockAuditItem {
    @Id @GeneratedValue(strategy=GenerationType.UUID) private UUID id;
    @Column(name="restaurant_id",nullable=false,updatable=false) private UUID restaurantId;
    @Column(name="stock_audit_id",nullable=false,updatable=false) private UUID stockAuditId;
    @Column(name="ingredient_id",nullable=false,updatable=false) private UUID ingredientId;
    @Column(name="ingredient_sku_snapshot",updatable=false,length=80) private String sku;
    @Column(name="ingredient_name_snapshot",nullable=false,updatable=false,length=150) private String name;
    @Column(name="unit_snapshot",nullable=false,updatable=false,length=30) private String unit;
    @Column(name="system_quantity",nullable=false,updatable=false,precision=14,scale=3) private BigDecimal systemQuantity;
    @Column(name="counted_quantity",precision=14,scale=3) private BigDecimal countedQuantity;
    @Column(name="reference_unit_cost_snapshot",nullable=false,updatable=false,precision=14,scale=2) private BigDecimal referenceUnitCostSnapshot;
    @Column(name="movement_count_snapshot",nullable=false,updatable=false) private long movementCountSnapshot;
    private String note;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    public StockAuditItem(StockAudit audit,TrackedIngredientSnapshot ingredient,BigDecimal quantity,BigDecimal cost,long movements,Instant now) {
        restaurantId=audit.getRestaurantId();stockAuditId=audit.getId();ingredientId=ingredient.id();sku=ingredient.sku();
        name=ingredient.name();unit=ingredient.baseUnit();systemQuantity=quantity;referenceUnitCostSnapshot=cost;
        movementCountSnapshot=movements;createdAt=now.truncatedTo(ChronoUnit.MICROS);updatedAt=createdAt;
    }
    public boolean count(BigDecimal value,String note,Instant now) {
        String nextNote=note==null?this.note:note.trim().isEmpty()?null:note.trim();
        if(countedQuantity!=null && countedQuantity.compareTo(value)==0 && Objects.equals(this.note,nextNote))return false;
        countedQuantity=value;this.note=nextNote;updatedAt=now.truncatedTo(ChronoUnit.MICROS);return true;
    }
    public BigDecimal difference() {return countedQuantity==null?null:countedQuantity.subtract(systemQuantity);}
}
