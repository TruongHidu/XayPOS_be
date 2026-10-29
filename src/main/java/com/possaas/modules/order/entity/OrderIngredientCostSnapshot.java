package com.possaas.modules.order.entity;
import java.math.BigDecimal;
public record OrderIngredientCostSnapshot(Boolean configured, BigDecimal unitCost, BigDecimal lineCost) {}
