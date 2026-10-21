package com.possaas.modules.order.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.AcceptedItem;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.LineState;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.Usage;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItem;
import com.possaas.modules.order.entity.OrderItemRecipeSnapshot;
import com.possaas.modules.order.entity.OrderItemRecipeUsage;
import com.possaas.modules.order.repository.OrderItemRecipeSnapshotRepository;
import com.possaas.modules.order.repository.OrderItemRecipeUsageRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** ORDER maps its immutable snapshots to the inventory port; inventory owns all stock decisions. */
@Component @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OrderInventoryAllocationOperation {
    private final OrderRecipeSnapshotService recipeSnapshots;
    private final OrderItemRecipeSnapshotRepository snapshots;
    private final OrderItemRecipeUsageRepository usages;
    private final OrderRecipeSnapshotPolicy snapshotPolicy;
    private final OrderInventoryAllocationCommand inventory;

    public void accept(Order order, List<OrderItem> accepted, CurrentUser actor, Instant now, String ip) {
        if (accepted.isEmpty()) return;
        recipeSnapshots.capture(order, accepted, actor, now, ip);
        var headers = snapshots.findAllByRestaurantIdAndOrderItemIdIn(actor.restaurantId(),
                accepted.stream().map(OrderItem::getId).toList()).stream()
                .collect(Collectors.toMap(OrderItemRecipeSnapshot::getOrderItemId, Function.identity()));
        var details = usages.findAllByRestaurantIdAndRecipeSnapshotIdInOrderByRecipeSnapshotIdAscIngredientIdAsc(
                actor.restaurantId(), headers.values().stream().map(OrderItemRecipeSnapshot::getId).toList())
                .stream().filter(OrderItemRecipeUsage::isTrackInventorySnapshot)
                .collect(Collectors.groupingBy(OrderItemRecipeUsage::getRecipeSnapshotId));
        var decisions = accepted.stream().map(line -> {
            var header = headers.get(line.getId());
            if (header == null) throw snapshotPolicy.conflict();
            snapshotPolicy.requireMatches(header, line);
            return new AcceptedItem(line.getId(), header.getId(), header.getSnapshotStatus().name(),
                    header.getOrderQuantitySnapshot(), header.getOrderUnitSnapshot(),
                    details.getOrDefault(header.getId(), List.of()).stream().map(usage -> new Usage(
                        usage.getId(), usage.getIngredientId(), usage.getIngredientSkuSnapshot(),
                        usage.getIngredientNameSnapshot(), usage.getIngredientUnitSnapshot(),
                        usage.getRequiredQuantity())).toList());
        }).toList();
        inventory.reserve(actor, order.getId(), decisions, now, ip);
    }

    public void validate(Order order, Collection<OrderItem> lines) {
        inventory.validate(order.getRestaurantId(), order.getId(), lines.stream().map(line ->
                new LineState(line.getId(), line.getStatus().name(), line.getSentToKitchenAt() != null,
                        line.getQuantity(), line.getUnit())).toList());
    }

    public void consume(Order order, Collection<OrderItem> lines, CurrentUser actor, Instant now, String ip) {
        if (!lines.isEmpty()) inventory.consume(actor, order.getId(), lines.stream().map(OrderItem::getId).toList(), now, ip);
    }

    public void release(Order order, Collection<OrderItem> lines, CurrentUser actor, Instant now, String ip) {
        var submitted = lines.stream().filter(line -> line.getSentToKitchenAt() != null).map(OrderItem::getId).toList();
        if (!submitted.isEmpty()) inventory.release(actor, order.getId(), submitted, now, ip);
    }
}
