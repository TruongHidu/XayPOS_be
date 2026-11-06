package com.possaas.modules.order.service;
import com.possaas.common.exception.*;
import com.possaas.modules.order.dto.OrderCostSnapshotResponse;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderCostSnapshotQueryService {
    private final OrderCostSnapshotAccess access;
    private final OrderRepository orders;
    private final OrderItemRepository lines;
    private final OrderItemRecipeSnapshotRepository snapshots;
    private final OrderRecipeSnapshotPolicy policy;
    public OrderCostSnapshotResponse find(UUID orderId,UUID lineId) {
        var actor=access.requireRead();
        var order=orders.findByIdAndRestaurantId(orderId,actor.restaurantId())
            .orElseThrow(()->new ResourceNotFoundException("ORDER_NOT_FOUND","Order not found"));
        var line=lines.findByIdAndRestaurantIdAndOrderId(lineId,actor.restaurantId(),orderId)
            .orElseThrow(()->new ResourceNotFoundException("ORDER_ITEM_NOT_FOUND","Order line not found"));
        var header=snapshots.findByRestaurantIdAndOrderItemId(actor.restaurantId(),lineId).orElse(null);
        if(header==null) {
            if(line.getSentToKitchenAt()!=null) throw policy.conflict();
            return new OrderCostSnapshotResponse(orderId,lineId,null,null,null,OrderCostStatus.NOT_SUBMITTED,null,
                line.getQuantity(),line.getUnit(),order.getCurrencyCode(),null,null,null);
        }
        policy.requireMatches(header,line);
        if(header.getCostSnapshotVersion()==null)
            return new OrderCostSnapshotResponse(orderId,lineId,null,null,header.getCostSourceSnapshot(),
                OrderCostStatus.LEGACY_UNAVAILABLE,null,header.getOrderQuantitySnapshot(),header.getOrderUnitSnapshot(),
                order.getCurrencyCode(),null,null,null);
        if(header.getCostSnapshotVersion()!=1||header.getCostStatus()==null)
            throw new ConflictException("ORDER_COST_SNAPSHOT_CONFLICT","Stored monetary decision is inconsistent");
        return new OrderCostSnapshotResponse(orderId,lineId,header.getCostSnapshotVersion(),"CATALOG_REFERENCE_AT_ACCEPTANCE",
            header.getCostSourceSnapshot(),header.getCostStatus(),header.getCostUnavailableReason(),
            header.getOrderQuantitySnapshot(),header.getOrderUnitSnapshot(),order.getCurrencyCode(),
            header.getUnitCostSnapshot(),header.getTotalCostSnapshot(),header.getCapturedAt());
    }
}
