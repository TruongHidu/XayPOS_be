package com.possaas.modules.order.service.cost;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery;
import com.possaas.modules.order.entity.*;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Batches live prices once for each source after acceptance guards and recipe decisions. */
@Component @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
public class OrderCostSnapshotPreparation {
    private final OrderReferenceCostQuery prices;
    private final OrderReferenceCostCalculator calculator;
    public Map<UUID,Prepared> prepare(Order order,List<Input> inputs) {
        if(inputs.isEmpty()) return Map.of();
        var manualIds=inputs.stream().filter(i->i.snapshotStatus()==RecipeSnapshotStatus.RECIPE_DISABLED)
            .map(Input::menuItemId).distinct().toList();
        var ingredientIds=inputs.stream().filter(i->i.snapshotStatus()==RecipeSnapshotStatus.CAPTURED)
            .flatMap(i->i.usages().stream()).map(Usage::ingredientId).distinct().toList();
        var manual=manualIds.isEmpty()?null:prices.findManualCosts(order.getRestaurantId(),manualIds);
        var ingredients=ingredientIds.isEmpty()?null:prices.findIngredientCosts(order.getRestaurantId(),ingredientIds);
        if((!manualIds.isEmpty()&&manual==null)||(!ingredientIds.isEmpty()&&ingredients==null))
            throw OrderReferenceCostCalculator.invalid();
        if(manual!=null) calculator.requireCurrency(order.getCurrencyCode(),manual.currencyCode());
        if(ingredients!=null) calculator.requireCurrency(order.getCurrencyCode(),ingredients.currencyCode());
        Map<UUID,Prepared> result=new LinkedHashMap<>();
        for(var input:inputs) {
            Prepared prepared;
            if(input.snapshotStatus()==RecipeSnapshotStatus.RECIPE_DISABLED) {
                prepared=new Prepared(calculator.manual(input.menuItemId(),input.orderUnit(),input.quantity(),
                    manual.values().get(input.menuItemId())),Map.of());
            } else if(input.snapshotStatus()==RecipeSnapshotStatus.CAPTURED) {
                Map<UUID,OrderIngredientCostSnapshot> values=new LinkedHashMap<>();
                for(var usage:input.usages()) {
                    if(ingredients==null||values.put(usage.ingredientId(),calculator.ingredient(usage.ingredientId(),usage.unit(),
                        usage.quantity(),ingredients.values().get(usage.ingredientId())))!=null) throw OrderReferenceCostCalculator.invalid();
                }
                prepared=new Prepared(calculator.recipe(input.quantity(),values.values()),values);
            } else if(input.snapshotStatus()==RecipeSnapshotStatus.FEATURE_DISABLED) {
                prepared=new Prepared(OrderCostSnapshot.unavailable(),Map.of());
            } else throw OrderReferenceCostCalculator.invalid();
            if(result.put(input.orderItemId(),prepared)!=null) throw OrderReferenceCostCalculator.invalid();
        }
        return Map.copyOf(result);
    }
    public record Input(UUID orderItemId,UUID menuItemId,String orderUnit,BigDecimal quantity,
            RecipeSnapshotStatus snapshotStatus,List<Usage> usages) { public Input { usages=List.copyOf(usages); } }
    public record Usage(UUID ingredientId,String unit,BigDecimal quantity) {}
    public record Prepared(OrderCostSnapshot summary,Map<UUID,OrderIngredientCostSnapshot> ingredients) {
        public Prepared { ingredients=Map.copyOf(ingredients); }
    }
}
