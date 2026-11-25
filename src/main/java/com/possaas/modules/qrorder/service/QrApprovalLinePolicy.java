package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.qrorder.dto.QrStaffRequests;
import com.possaas.modules.qrorder.entity.QrOrderRequestItem;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Splits a reviewed request into the lines staff approve and the lines they leave out. Pure decision:
 * it never writes; the approval chain applies the result inside its locked transaction.
 */
@Component @RequiredArgsConstructor
public class QrApprovalLinePolicy {
    private final QrQuoteService quotes;

    public Decision decide(List<QrOrderRequestItem> items, QrQuoteService.QrQuote reviewed,
            List<QrStaffRequests.ExcludedItem> excludedItems) {
        if (reviewed.lines().size() != items.size()) throw new IllegalStateException("Reviewed quote does not match request lines");
        var reasons = new HashMap<UUID,String>();
        var known = new HashSet<UUID>(); items.forEach(item -> known.add(item.getId()));
        for (var excluded : excludedItems) {
            if (!known.contains(excluded.requestItemId()) || reasons.put(excluded.requestItemId(), excluded.reason().trim()) != null)
                throw invalid();
        }
        var included = new ArrayList<QrOrderRequestItem>(); var includedQuote = new ArrayList<QrQuoteService.Line>();
        var left = new ArrayList<Excluded>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i); var line = reviewed.lines().get(i);
            if (!Objects.equals(line.requestItemId(), item.getId())) throw new IllegalStateException("Reviewed quote is out of line order");
            var reason = reasons.get(item.getId());
            if (reason != null) left.add(new Excluded(item, reason));
            else if (!line.available()) throw unavailable(item);
            else { included.add(item); includedQuote.add(line); }
        }
        if (included.isEmpty())
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QR_APPROVAL_EMPTY",
                "Every line was left out; reject the request instead");
        return new Decision(List.copyOf(included), List.copyOf(includedQuote), List.copyOf(left), quotes.amount(includedQuote));
    }

    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "QR_EXCLUDED_ITEM_INVALID",
            "Excluded items must be distinct lines of this request");
    }
    private static ConflictException unavailable(QrOrderRequestItem item) {
        return new ConflictException("QR_LINE_UNAVAILABLE",
            "Line " + item.getLineNumber() + " is unavailable; leave it out or reject the request");
    }

    /** {@code approvedAmount} is the current price of the included lines only. */
    public record Decision(List<QrOrderRequestItem> included, List<QrQuoteService.Line> includedQuote,
            List<Excluded> excluded, BigDecimal approvedAmount) {
        public List<UUID> excludedIds() { return excluded.stream().map(e -> e.item().getId()).toList(); }
    }
    public record Excluded(QrOrderRequestItem item, String reason) {}
}
