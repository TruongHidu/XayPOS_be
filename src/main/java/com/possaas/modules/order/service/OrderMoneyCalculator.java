package com.possaas.modules.order.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.order.entity.*;
import java.math.*;
import java.util.Collection;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OrderMoneyCalculator {
    private static final BigDecimal MAX_MONEY=new BigDecimal("999999999999.99");
    public BigDecimal quantity(BigDecimal value) {
        if(value==null || value.signum()<=0 || value.stripTrailingZeros().scale()>3 || value.compareTo(new BigDecimal("999999999.999"))>0)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Quantity must be positive with at most three decimal places");
        return value.setScale(3,RoundingMode.UNNECESSARY);
    }
    public BigDecimal amount(BigDecimal value) {
        var result=value.setScale(2,RoundingMode.HALF_UP);
        if(result.signum()<0 || result.compareTo(MAX_MONEY)>0)
            throw new BusinessException(HttpStatus.BAD_REQUEST,"ORDER_AMOUNT_LIMIT_EXCEEDED","Order amount exceeds supported precision");
        return result;
    }
    public BigDecimal line(BigDecimal quantity,BigDecimal price) { return amount(quantity.multiply(price)); }
    public void recalculate(Order order,Collection<OrderItem> lines) {
        apply(order,calculate(order,lines));
    }
    public Totals calculate(Order order,Collection<OrderItem> lines) {
        return calculate(order,lines,OrderItem::getLineTotal);
    }
    public Totals calculate(Order order,Collection<OrderItem> lines,Function<OrderItem,BigDecimal> lineAmount) {
        var subtotal=amount(lines.stream().filter(l->l.getStatus()!=OrderItemStatus.CANCELLED)
            .map(lineAmount).reduce(BigDecimal.ZERO,BigDecimal::add));
        return new Totals(subtotal,amount(subtotal.subtract(order.getDiscountAmount()).add(order.getTaxAmount()).add(order.getServiceChargeAmount())));
    }
    public void apply(Order order,Totals totals) {
        order.setSubtotalAmount(totals.subtotal()); order.setTotalAmount(totals.total());
    }
    public record Totals(BigDecimal subtotal,BigDecimal total) {}
}
