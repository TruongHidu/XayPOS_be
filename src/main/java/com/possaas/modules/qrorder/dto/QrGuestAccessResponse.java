package com.possaas.modules.qrorder.dto;

import com.possaas.modules.qrorder.entity.QrEntryType;
import com.possaas.modules.order.entity.*;
import java.time.Instant;
import java.util.UUID;

public record QrGuestAccessResponse(String guestToken,Instant expiresAt,QrEntryType entryType,
        ServiceType serviceType,SourceChannel sourceChannel,RestaurantMetadata restaurant,TableMetadata table) {
    public record RestaurantMetadata(UUID id,String name,String currencyCode) {}
    public record TableMetadata(UUID id,String code,String name) {}
    @Override public String toString() { return "QrGuestAccessResponse[redacted]"; }
}
