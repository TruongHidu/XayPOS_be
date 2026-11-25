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

    /** Guest submission: every line must be sellable, otherwise nothing is accepted. */
    public QrQuote quote(UUID tenant,String currencyCode,List<QrOrderRequestItem> items) {
        var snapshots=menu.requireSellable(tenant,items.stream().map(QrOrderRequestItem::getItemId).toList());
        List<Line> result=new ArrayList<>();
        for(var requested:items) {
            var source=snapshots.get(requested.getItemId());
            result.add(priced(requested,source.name(),source.unit(),source.salePrice()));
        }
        return new QrQuote(currencyCode,amount(result),List.copyOf(result));
    }

    /**
     * Staff review: each line is judged on its own. An unavailable line keeps the guest's submitted name/unit,
     * has no current price and carries the menu's reason code, so staff can approve the rest without it.
     */
    public QrQuote review(UUID tenant,String currencyCode,List<QrOrderRequestItem> items) {
        var judged=menu.inspectSellable(tenant,items.stream().map(QrOrderRequestItem::getItemId).toList());
        List<Line> result=new ArrayList<>();
        for(var requested:items) {
            var sellability=judged.get(requested.getItemId());
            if(sellability.isSellable()) {
                var source=sellability.item();
                result.add(priced(requested,source.name(),source.unit(),source.salePrice()));
            } else {
                result.add(new Line(requested.getId(),requested.getItemId(),money.quantity(requested.getQuantity()),requested.getNote(),
                        requested.getQuotedItemName(),requested.getQuotedUnit(),null,null,false,sellability.unavailableCode()));
            }
        }
        return new QrQuote(currencyCode,amount(result),List.copyOf(result));
    }

    /** Money total of the given available lines, rounded like every other order amount. */
    public BigDecimal amount(Collection<Line> lines) {
        BigDecimal amount=BigDecimal.ZERO;
        for(var line:lines) if(line.available()) amount=money.amount(amount.add(line.lineTotal()));
        return money.amount(amount);
    }

    private Line priced(QrOrderRequestItem requested,String name,String unit,BigDecimal salePrice) {
        var quantity=money.quantity(requested.getQuantity()); var price=money.amount(salePrice);
        return new Line(requested.getId(),requested.getItemId(),quantity,requested.getNote(),name,unit,price,money.line(quantity,price),true,null);
    }

    /** {@code amount} counts available lines only. */
    public record QrQuote(String currencyCode,BigDecimal amount,List<Line> lines) {
        public boolean allUnavailable() { return !lines.isEmpty() && lines.stream().noneMatch(Line::available); }
    }
    /** unitPrice/lineTotal are null and unavailableReason is set when {@code available=false}. */
    public record Line(UUID requestItemId,UUID itemId,BigDecimal quantity,String note,String itemName,String unit,
            BigDecimal unitPrice,BigDecimal lineTotal,boolean available,String unavailableReason) {
        @Override public String toString() { return "QrQuoteLine[itemId="+itemId+"]"; }
    }
}
