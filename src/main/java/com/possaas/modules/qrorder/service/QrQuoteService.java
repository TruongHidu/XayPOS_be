package com.possaas.modules.qrorder.service;

import com.possaas.modules.menu.application.port.OrderMenuQuery;
import com.possaas.modules.order.service.OrderMoneyCalculator;
import com.possaas.modules.qrorder.entity.QrOrderRequestItem;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Pricing/sellability use the same authoritative MENU boundary as staff ordering. */
@Component @RequiredArgsConstructor
public class QrQuoteService {
    private final OrderMenuQuery menu;
    private final OrderMoneyCalculator money;
    public QrQuote quote(UUID tenant,String currencyCode,List<QrOrderRequestItem> items) {
        var snapshots=menu.requireSellable(tenant,items.stream().map(QrOrderRequestItem::getItemId).toList());
        List<Line> result=new ArrayList<>(); BigDecimal amount=BigDecimal.ZERO;
        for(var requested:items) {
            var source=snapshots.get(requested.getItemId());
            var quantity=money.quantity(requested.getQuantity()); var price=money.amount(source.salePrice());
            var total=money.line(quantity,price);
            result.add(new Line(source.id(),quantity,requested.getNote(),source.name(),source.unit(),price,total));
            amount=money.amount(amount.add(total));
        }
        return new QrQuote(currencyCode,money.amount(amount),List.copyOf(result));
    }
    public record QrQuote(String currencyCode,BigDecimal amount,List<Line> lines) {}
    public record Line(UUID itemId,BigDecimal quantity,String note,String itemName,String unit,BigDecimal unitPrice,BigDecimal lineTotal) {
        @Override public String toString() { return "QrQuoteLine[itemId="+itemId+"]"; }
    }
}
