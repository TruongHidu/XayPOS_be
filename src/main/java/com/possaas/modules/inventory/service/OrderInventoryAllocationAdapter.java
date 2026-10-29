package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.application.port.RecipeUsageCostQuery;
import com.possaas.modules.inventory.application.port.RecipeUsageCostQuery.UsageCost;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal commands join the ORDER transaction after its restaurant and order locks. */
@Service @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class OrderInventoryAllocationAdapter implements OrderInventoryAllocationCommand {
    private static final BigDecimal ZERO = new BigDecimal("0.000");
    private final OrderItemInventoryAllocationRepository allocations;
    private final InventoryReservationRepository reservations;
    private final InventoryBalanceRepository balances;
    private final InventoryTransactionWriteRepository transactions;
    private final WarehouseRepository warehouses;
    private final RecipeUsageCostQuery usageCosts;
    private final InventoryAllocationPolicy policy;
    private final InventoryAvailabilityPolicy availability;
    private final InventoryAllocationAudit audit;
    private final LowStockAlertReconciliationService alerts;

    @Override
    public void reserve(CurrentUser actor, UUID orderId, List<AcceptedItem> items, boolean inventoryEnabled,
            Instant now, String ip) {
        if (items.isEmpty()) return;
        requireInput(items);
        List<UUID> ids = items.stream().map(AcceptedItem::orderItemId).toList();
        State state = load(actor.restaurantId(), orderId,
                allocations.findAllForUpdate(actor.restaurantId(), ids));
        List<AcceptedItem> fresh = new ArrayList<>();
        for (AcceptedItem item : items) {
            OrderItemInventoryAllocation existing = state.headers().get(item.orderItemId());
            if (existing == null) fresh.add(item);
            else {
                policy.matches(existing, actor.restaurantId(), orderId, item);
                policy.matchesUsages(existing, item, state.details(existing));
            }
        }
        boolean enabled = !fresh.isEmpty() && inventoryEnabled;
        Map<UUID, InventoryAllocationSkipReason> decisions = new HashMap<>();
        boolean needsWarehouse = false;
        for (AcceptedItem item : fresh) {
            InventoryAllocationSkipReason reason = policy.skipReason(item, enabled);
            decisions.put(item.orderItemId(), reason);
            needsWarehouse |= reason == null;
        }
        UUID warehouseId = needsWarehouse ? warehouses
                .findByRestaurantIdAndDefaultWarehouseTrueAndDeletedAtIsNull(actor.restaurantId())
                .filter(Warehouse::isActive).map(Warehouse::getId)
                .orElseThrow(() -> new ConflictException("INVENTORY_DEFAULT_WAREHOUSE_REQUIRED",
                        "Configure an active default warehouse before submitting this order")) : null;
        Map<BalanceKey, BigDecimal> demand = new HashMap<>();
        for (AcceptedItem item : fresh) {
            if (decisions.get(item.orderItemId()) != null) continue;
            for (Usage usage : item.usages()) add(demand, new BalanceKey(warehouseId, usage.ingredientId()), usage.quantity());
        }
        Set<BalanceKey> keys = new HashSet<>(state.keys()); keys.addAll(demand.keySet());
        Map<BalanceKey, InventoryBalance> locked = balanceState(actor.restaurantId(), keys, true, state.keys());
        demand.forEach((key, quantity) -> availability.requireAvailable(locked.get(key), quantity));

        List<OrderItemInventoryAllocation> created = fresh.stream().map(item ->
                new OrderItemInventoryAllocation(actor.restaurantId(), orderId, item.orderItemId(), item.recipeSnapshotId(),
                        item.orderQuantity(), item.orderUnit(), decisions.get(item.orderItemId()) == null ? warehouseId : null,
                        decisions.get(item.orderItemId()), now)).toList();
        if (created.isEmpty()) return;
        allocations.saveAllAndFlush(created);
        Map<UUID, AcceptedItem> input = items.stream().collect(Collectors.toMap(AcceptedItem::orderItemId, Function.identity()));
        List<InventoryReservation> details = new ArrayList<>();
        for (OrderItemInventoryAllocation allocation : created) {
            if (allocation.getStatus() != InventoryAllocationStatus.RESERVED) continue;
            for (Usage usage : input.get(allocation.getOrderItemId()).usages())
                details.add(new InventoryReservation(allocation, usage.recipeUsageId(), usage.ingredientId(), usage.sku(),
                        usage.name(), usage.unit(), availability.positive(usage.quantity()), now));
        }
        if (!details.isEmpty()) reservations.saveAllAndFlush(details);
        demand.forEach((key, quantity) -> locked.get(key).applyReservationChange(quantity, now));
        if (!demand.isEmpty()) balances.saveAllAndFlush(demand.keySet().stream().map(locked::get).toList());
        audit.record(actor, created, ip);
    }

    @Override
    public void consume(CurrentUser actor, UUID orderId, Collection<UUID> itemIds, Instant now, String ip) {
        transition(actor, orderId, itemIds, now, ip, true);
    }

    @Override
    public void release(CurrentUser actor, UUID orderId, Collection<UUID> itemIds, Instant now, String ip) {
        transition(actor, orderId, itemIds, now, ip, false);
    }

    private void transition(CurrentUser actor, UUID orderId, Collection<UUID> itemIds, Instant now, String ip,
            boolean consume) {
        if (itemIds.isEmpty()) return;
        if (itemIds.stream().anyMatch(Objects::isNull)) throw InventoryAllocationPolicy.conflict();
        List<UUID> ids = itemIds.stream().distinct().sorted().toList();
        State state = load(actor.restaurantId(), orderId, allocations.findAllForUpdate(actor.restaurantId(), ids));
        if (state.headers().size() != ids.size()) throw InventoryAllocationPolicy.missing();
        Map<BalanceKey, InventoryBalance> locked = balanceState(actor.restaurantId(), state.keys(), true, state.keys());
        List<OrderItemInventoryAllocation> changed = new ArrayList<>();
        for (UUID id : ids) {
            OrderItemInventoryAllocation allocation = state.headers().get(id);
            if (allocation.getStatus() == InventoryAllocationStatus.SKIPPED
                    || allocation.getStatus() == (consume ? InventoryAllocationStatus.CONSUMED : InventoryAllocationStatus.RELEASED))
                continue;
            if (allocation.getStatus() != InventoryAllocationStatus.RESERVED) throw InventoryAllocationPolicy.transition();
            for (InventoryReservation reservation : state.details(allocation)) {
                InventoryBalance balance = locked.get(new BalanceKey(reservation.getWarehouseId(), reservation.getIngredientId()));
                BigDecimal amount = availability.positive(reservation.getQuantity());
                if (balance.getReservedQuantity().compareTo(amount) < 0 || balance.getQuantity().compareTo(amount) < 0)
                    throw InventoryAvailabilityPolicy.invariant();
                if (consume) {
                    BigDecimal before = balance.getQuantity();
                    BigDecimal after = availability.quantity(before.subtract(amount));
                    balance.applyQuantityChange(amount.negate(), now);
                    BigDecimal unitCost = policy.usageUnitCost(allocation, reservation,
                            state.costs().get(reservation.getRecipeUsageId()));
                    transactions.append(InventoryTransaction.recipeUse(allocation, reservation, before, after,
                            unitCost, actor.userId(), now));
                }
                balance.applyReservationChange(amount.negate(), now);
            }
            if (consume) allocation.consume(now); else allocation.release(now);
            changed.add(allocation);
        }
        if (changed.isEmpty()) return;
        balances.saveAll(locked.values());
        allocations.saveAllAndFlush(changed);
        transactions.flush();
        if (consume) alerts.changed(actor, locked.values(), ip);
        audit.record(actor, changed, ip);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void validate(UUID tenant, UUID orderId, List<LineState> lines) {
        if (lines.isEmpty()) return;
        Set<UUID> ids = new HashSet<>();
        for (LineState line : lines) if (line.orderItemId() == null || !ids.add(line.orderItemId()))
            throw InventoryAllocationPolicy.conflict();
        State state = load(tenant, orderId, allocations.findAllByRestaurantIdAndOrderItemIdIn(tenant, ids));
        for (LineState line : lines) policy.line(state.headers().get(line.orderItemId()), line);
        balanceState(tenant, state.keys(), false, state.keys());
    }

    private State load(UUID tenant, UUID orderId, List<OrderItemInventoryAllocation> headers) {
        for (OrderItemInventoryAllocation header : headers)
            if (!tenant.equals(header.getRestaurantId()) || !orderId.equals(header.getOrderId()))
                throw InventoryAllocationPolicy.conflict();
        List<InventoryReservation> details = headers.isEmpty() ? List.of() : reservations.findAllForAllocations(tenant,
                headers.stream().map(OrderItemInventoryAllocation::getId).toList());
        Map<UUID, List<InventoryReservation>> byAllocation = details.stream().collect(Collectors.groupingBy(InventoryReservation::getAllocationId));
        List<UUID> usageIds = details.stream().map(InventoryReservation::getRecipeUsageId).distinct().toList();
        Map<UUID, UsageCost> costs = usageIds.isEmpty() ? Map.of() : usageCosts.findCosts(tenant, usageIds);
        if (costs == null) throw InventoryAllocationPolicy.conflict();
        Map<UUID, InventoryTransaction> ledger = new HashMap<>();
        for (InventoryTransaction transaction : transactions.findForReservations(tenant,
                details.stream().map(InventoryReservation::getId).toList()))
            if (ledger.put(transaction.getReservationId(), transaction) != null) throw InventoryAllocationPolicy.conflict();
        for (OrderItemInventoryAllocation header : headers)
            policy.history(header, byAllocation.getOrDefault(header.getId(), List.of()), ledger, costs);
        return new State(headers.stream().collect(Collectors.toMap(OrderItemInventoryAllocation::getOrderItemId, Function.identity())),
                byAllocation, details.stream().map(detail -> new BalanceKey(detail.getWarehouseId(), detail.getIngredientId()))
                    .collect(Collectors.toSet()), costs);
    }

    private Map<BalanceKey, InventoryBalance> balanceState(UUID tenant, Set<BalanceKey> keys, boolean lock,
            Set<BalanceKey> requiredRows) {
        if (keys.isEmpty()) return Map.of();
        List<UUID> warehouseIds = keys.stream().map(BalanceKey::warehouseId).distinct().toList();
        List<UUID> ingredientIds = keys.stream().map(BalanceKey::ingredientId).distinct().toList();
        var found = lock ? balances.findAllInWarehousesForUpdate(tenant, warehouseIds, ingredientIds)
                : balances.findAllByRestaurantIdAndWarehouseIdInAndItemIdIn(tenant, warehouseIds, ingredientIds);
        Map<BalanceKey, InventoryBalance> result = found.stream().collect(Collectors.toMap(
                balance -> new BalanceKey(balance.getWarehouseId(), balance.getItemId()), Function.identity()));
        Map<BalanceKey, BigDecimal> actual = reservations.sumReserved(tenant, warehouseIds, ingredientIds).stream()
                .collect(Collectors.toMap(sum -> new BalanceKey(sum.getWarehouseId(), sum.getIngredientId()),
                        InventoryReservationRepository.ReservedQuantity::getQuantity));
        for (BalanceKey key : keys) {
            InventoryBalance balance = result.get(key);
            BigDecimal reserved = actual.getOrDefault(key, ZERO);
            if (balance == null) {
                if (requiredRows.contains(key) || reserved.signum() != 0) throw InventoryAvailabilityPolicy.invariant();
            } else availability.invariant(balance, reserved);
        }
        return result;
    }

    private void requireInput(List<AcceptedItem> items) {
        Set<UUID> itemIds = new HashSet<>();
        for (AcceptedItem item : items) {
            if (item.orderItemId() == null || !itemIds.add(item.orderItemId()) || item.recipeSnapshotId() == null
                    || item.snapshotStatus() == null || item.orderUnit() == null || item.orderUnit().isBlank())
                throw InventoryAllocationPolicy.conflict();
            availability.positive(item.orderQuantity());
            Set<UUID> usageIds = new HashSet<>(); Set<UUID> ingredientIds = new HashSet<>();
            for (Usage usage : item.usages()) {
                if (usage.recipeUsageId() == null || !usageIds.add(usage.recipeUsageId()) || usage.ingredientId() == null
                        || !ingredientIds.add(usage.ingredientId()) || usage.name() == null || usage.name().isBlank()
                        || usage.unit() == null || usage.unit().isBlank()) throw InventoryAllocationPolicy.conflict();
                availability.positive(usage.quantity());
            }
        }
    }

    private void add(Map<BalanceKey, BigDecimal> quantities, BalanceKey key, BigDecimal amount) {
        quantities.put(key, availability.quantity(quantities.getOrDefault(key, ZERO).add(availability.positive(amount))));
    }

    private record BalanceKey(UUID warehouseId, UUID ingredientId) {}
    private record State(Map<UUID, OrderItemInventoryAllocation> headers,
            Map<UUID, List<InventoryReservation>> reservations, Set<BalanceKey> keys, Map<UUID, UsageCost> costs) {
        List<InventoryReservation> details(OrderItemInventoryAllocation allocation) {
            return reservations.getOrDefault(allocation.getId(), List.of());
        }
    }
}
