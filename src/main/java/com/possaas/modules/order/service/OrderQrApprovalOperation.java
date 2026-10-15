package com.possaas.modules.order.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.application.port.QrApprovedOrderCommand;
import com.possaas.modules.order.application.port.QrOrderCheckoutQuery;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.strategy.OrderCreationContext;
import com.possaas.modules.order.service.strategy.OrderCreationPlan;
import com.possaas.modules.table.application.port.OrderSeatingCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** QR approves through the same strategy, line, money, confirmation, history and audit operations. */
@Component @RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class OrderQrApprovalOperation implements QrApprovedOrderCommand {
    private final OrderFactory factory;
    private final OrderSessionServingPolicy serving;
    private final OrderAppendPolicy append;
    private final QrOrderCheckoutQuery checkout;
    private final OrderCodeGenerator codes;
    private final OrderLineFactory lineFactory;
    private final OrderMoneyCalculator money;
    private final OrderConfirmationOperation confirmation;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderHistory history;
    private final OrderAudit audit;
    private final OrderServingStatusPolicy servingStatus;
    private final OrderSeatingCommand seating;
    private final OrderResponseMapper mapper;

    @Override public Preparation prepare(Intent c) {
        if (!c.actor().hasPermission("ORDER_CREATE") || !c.actor().hasPermission("ORDER_UPDATE"))
            throw new AccessDeniedException("Required order approval permissions are missing");
        boolean table = c.serviceType() == ServiceType.DINE_IN && c.sourceChannel() == SourceChannel.QR_TABLE;
        boolean takeaway = c.serviceType() == ServiceType.TAKEAWAY && c.sourceChannel() == SourceChannel.QR_STATIC;
        if (!table && !takeaway) throw new IllegalArgumentException("Unsupported trusted QR origin");
        if (takeaway && (c.tableId() != null || c.targetSessionId() != null))
            throw new ConflictException("TAKEAWAY_SESSION_NOT_ALLOWED", "Takeaway cannot use seating");
        var plan = factory.prepare(c.serviceType(), new OrderCreationContext(c.actor().restaurantId(),
            c.actor().userId(), c.currencyCode(), c.targetSessionId(), null, c.now(),
            table && c.targetSessionId() == null ? c.tableId() : null));
        if (table && c.targetSessionId() == null && plan.kind() != OrderCreationPlan.Kind.NEW_SESSION)
            throw new ConflictException("QR_REQUEST_SESSION_CHANGED", "Seating changed; review and select the current session");
        Order root = null;
        if (table && plan.kind() == OrderCreationPlan.Kind.EXISTING_SESSION) {
            checkout.requireAccepting(c.actor().restaurantId(), plan.tableSessionId());
            root = serving.findServing(c.actor().restaurantId(), plan.tableSessionId()).orElse(null);
        }
        boolean create = root == null;
        List<OrderItem> existing = List.of();
        Map<String,Object> before = null;
        if (create) {
            root = new Order(); root.initialize(c.actor().restaurantId(), c.now());
            root.setOrderCode(codes.generate()); root.setServiceType(c.serviceType()); root.setSourceChannel(c.sourceChannel());
            root.setTableSessionId(plan.tableSessionId()); root.setGuestCount(plan.guestCount()); root.setCurrencyCode(c.currencyCode());
            root.setCreatedBy(c.actor().userId()); root.setCustomerName(c.customerName()); root.setCustomerPhone(c.customerPhone());
            root.setNote(c.note()); root.setIdempotencyKey("qr-approval:" + c.requestId()); root.setRequestHash(c.requestHash());
        } else {
            if (root.getStatus() == OrderStatus.OPEN)
                throw new ConflictException("QR_ORDER_DRAFT_NOT_SUBMITTED", "Submit the staff draft before approving QR additions");
            append.requireAppendable(root, OrderSubmissionMode.SUBMIT, c.currencyCode());
            existing = items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(c.actor().restaurantId(), root.getId());
            before = audit.snapshot(root, existing);
        }
        int first = existing.stream().mapToInt(OrderItem::getLineNumber).max().orElse(0) + 1;
        var added = lineFactory.create(root, c.items(), first, c.now());
        requireReviewedQuote(added, c.reviewedQuote());
        var combined = new ArrayList<>(existing); combined.addAll(added);
        var totals = money.calculate(root, combined);
        if (create) { money.apply(root, totals); confirmation.validate(root, combined, c.actor()); }
        return new Prepared(c, root, added, combined, plan, create, totals, before);
    }

    @Override public Result persist(Preparation preparation) {
        if (!(preparation instanceof Prepared p)) throw new IllegalArgumentException("Unknown approval preparation");
        var c = p.intent(); var root = p.order();
        if (p.create()) {
            if (p.seating().kind() == OrderCreationPlan.Kind.NEW_SESSION)
                root.setTableSessionId(seating.open(c.actor().restaurantId(), p.seating().tableId(),
                    p.seating().guestCount(), c.now(), c.ip()));
            orders.save(root); p.added().forEach(line -> line.setOrderId(root.getId()));
            items.saveAllAndFlush(p.added()); history.created(root, p.added(), c.actor().userId(), c.now());
            audit.record(c.actor(), "ORDER_CREATED", root, null, p.combined(), c.ip()); orders.flush();
            confirmation.confirm(root, p.combined(), c.actor(), c.now(), c.ip());
        } else {
            p.added().forEach(line -> line.setSentToKitchenAt(c.now()));
            items.saveAllAndFlush(p.added()); money.apply(root, p.totals());
            root.setStatus(servingStatus.aggregate(root, p.combined())); root.changed(c.now()); orders.saveAndFlush(root);
            history.created(root, p.added(), c.actor().userId(), c.now());
            audit.record(c.actor(), "ORDER_ITEMS_ADDED", root, p.before(), p.combined(), c.ip());
        }
        orders.flush();
        return new Result(mapper.detail(root, p.combined()), p.added().stream().map(OrderItem::getId).toList());
    }

    private void requireReviewedQuote(List<OrderItem> added, List<QuoteLine> reviewed) {
        if (added.size() != reviewed.size()) throw changedQuote();
        for (int i = 0; i < added.size(); i++) {
            var actual = added.get(i); var expected = reviewed.get(i);
            if (!Objects.equals(actual.getItemId(), expected.itemId()) || !Objects.equals(actual.getItemName(), expected.name())
                    || !Objects.equals(actual.getUnit(), expected.unit()) || actual.getQuantity().compareTo(expected.quantity()) != 0
                    || actual.getUnitPrice().compareTo(expected.unitPrice()) != 0 || actual.getLineTotal().compareTo(expected.lineTotal()) != 0)
                throw changedQuote();
        }
    }
    private ConflictException changedQuote() { return new ConflictException("QR_REVIEW_CONTEXT_CHANGED", "Quote changed; review again before approving"); }
    private record Prepared(Intent intent, Order order, List<OrderItem> added, List<OrderItem> combined,
            OrderCreationPlan seating, boolean create, OrderMoneyCalculator.Totals totals,
            Map<String,Object> before) implements Preparation {}
}
