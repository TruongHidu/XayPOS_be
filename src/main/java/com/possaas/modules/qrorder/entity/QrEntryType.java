package com.possaas.modules.qrorder.entity;

import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;

/** Server-owned origin, never accepted in a guest command. */
public enum QrEntryType {
    TABLE, RESTAURANT;
    public ServiceType serviceType() { return this == TABLE ? ServiceType.DINE_IN : ServiceType.TAKEAWAY; }
    public SourceChannel sourceChannel() { return this == TABLE ? SourceChannel.QR_TABLE : SourceChannel.QR_STATIC; }
}
