package com.possaas.modules.order.service.cost;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.application.port.OrderReferenceCostQuery.Price;
import com.possaas.modules.order.entity.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reference-cost V1 uses final immutable required quantities, not recipe batch estimator quantities. */
@Component
public class OrderReferenceCostCalculator {
    private static final BigDecimal PRICE_MAX=new BigDecimal("999999999999.99");
    public OrderCostSnapshot manual(UUID itemId, String unit, BigDecimal quantity, Price price) {
        requireQuantity(quantity);requirePrice(itemId,unit,price);
        if(!price.costConfigured()) return OrderCostSnapshot.incomplete(OrderCostUnavailableReason.MANUAL_COST_NOT_CONFIGURED);
        var cost=money(price.costPrice());
        return OrderCostSnapshot.complete(cost,money(cost.multiply(quantity)));
    }
    public OrderIngredientCostSnapshot ingredient(UUID id,String unit,BigDecimal required,Price price) {
        requireQuantity(required);requirePrice(id,unit,price);
        if(!price.costConfigured()) return new OrderIngredientCostSnapshot(false,null,null);
        var cost=money(price.costPrice());
        return new OrderIngredientCostSnapshot(true,cost,money(required.multiply(cost)));
    }
    public OrderCostSnapshot recipe(BigDecimal quantity,Collection<OrderIngredientCostSnapshot> ingredients) {
        requireQuantity(quantity);
        if(ingredients==null||ingredients.isEmpty()) throw invalid();
        BigDecimal total=new BigDecimal("0.00");boolean complete=true;
        for(var ingredient:ingredients) {
            if(ingredient==null||ingredient.configured()==null) throw invalid();
            if(!ingredient.configured()) {
                if(ingredient.unitCost()!=null||ingredient.lineCost()!=null) throw invalid();
                complete=false;
            } else {
                if(ingredient.unitCost()==null||ingredient.lineCost()==null) throw invalid();
                money(ingredient.unitCost());total=money(total.add(money(ingredient.lineCost())));
            }
        }
        return complete ? OrderCostSnapshot.complete(money(total.divide(quantity,2,RoundingMode.HALF_UP)),total)
                : OrderCostSnapshot.incomplete(OrderCostUnavailableReason.INGREDIENT_COST_NOT_CONFIGURED);
    }
    public void requireCurrency(String orderCurrency,String sourceCurrency) {
        if(orderCurrency==null||sourceCurrency==null||!orderCurrency.equals(sourceCurrency))
            throw new ConflictException("ORDER_COST_CURRENCY_MISMATCH","Reference cost currency differs from the order currency");
    }
    private void requirePrice(UUID id,String unit,Price price) {
        if(price==null||id==null||!id.equals(price.itemId())||price.costPrice()==null
            ||price.costPrice().signum()<0||price.costPrice().scale()>2
            ||price.costPrice().compareTo(PRICE_MAX)>0||(!price.costConfigured()&&price.costPrice().signum()!=0)) throw invalid();
        if(unit==null||!unit.equals(price.baseUnit()))
            throw new ConflictException("ORDER_COST_UNIT_MISMATCH","Reference cost unit differs from the accepted snapshot unit");
    }
    private void requireQuantity(BigDecimal quantity) { if(quantity==null||quantity.signum()<=0) throw invalid(); }
    private BigDecimal money(BigDecimal value) {
        if(value==null||value.signum()<0) throw invalid();
        var rounded=value.setScale(2,RoundingMode.HALF_UP);
        if(rounded.precision()-rounded.scale()>36)
            throw new ConflictException("ORDER_COST_AMOUNT_OUT_OF_RANGE","Reference cost amount exceeds NUMERIC(38,2)");
        return rounded;
    }
    public static ConflictException invalid() {
        return new ConflictException("INVALID_ORDER_COST_SNAPSHOT_DATA","Reference cost source or calculation data is invalid");
    }
}
