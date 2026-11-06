package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.QrTableAccessRepository;
import com.possaas.modules.qrorder.repository.QrGuestAuthorizationRepository;
import com.possaas.modules.restaurant.entity.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.util.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Credential/seating validity and entry-specific entitlement remain separate from persistence. */
@Component @RequiredArgsConstructor
public class QrScopePolicy {
    private final RestaurantRepository restaurants;
    private final RestaurantTableRepository tables;
    private final TableAreaRepository areas;
    private final QrTableAccessRepository access;
    private final FeatureAccessChecker features;
    private final QrGuestAuthorizationRepository grants;
    private final Clock clock;

    public Scope validate(QrGuestAuthorization grant) {
        var state=credentialState(grant.getRestaurantId(),grant.getEntryType(),grant.getTableId(),grant.getTableAccessEpoch(),grant.getCredentialHash());
        requireFeatures(grant.getRestaurantId(),grant.getEntryType()); return state;
    }
    public Scope validate(QrOrderRequest request) {
        var state=credentialState(request); requireFeatures(request.getRestaurantId(),request.getEntryType()); return state;
    }
    public Scope credentialState(QrOrderRequest request) {
        grants.findByIdAndRestaurantId(request.getGuestAuthorizationId(),request.getRestaurantId())
                .filter(g->g.getGuestId().equals(request.getGuestId()) && g.getRevokedAt()==null
                        && g.getExpiresAt().isAfter(clock.instant()) && g.getEntryType()==request.getEntryType()
                        && Objects.equals(g.getTableId(),request.getTableId())
                        && Objects.equals(g.getTableAccessEpoch(),request.getTableAccessEpoch())
                        && g.getCredentialHash().equals(request.getCredentialHash())).orElseThrow(QrScopePolicy::unavailable);
        return credentialState(request.getRestaurantId(),request.getEntryType(),request.getTableId(),request.getTableAccessEpoch(),request.getCredentialHash());
    }
    public boolean isCredentialValid(QrOrderRequest request) {
        try { credentialState(request); return true; } catch (BusinessException unavailable) { return false; }
    }
    public boolean isValid(QrOrderRequest request) {
        try { validate(request); return true; } catch (BusinessException unavailable) { return false; }
    }
    public long currentEpoch(UUID tenant,UUID tableId) {
        return access.findByTableIdAndRestaurantId(tableId,tenant).map(QrTableAccess::getEpoch).orElse(0L);
    }
    public void requireFeatures(UUID tenant,QrEntryType type) {
        try {
            features.requireFeature(tenant,"QR_MENU_VIEW");
            features.requireFeature(tenant,"ORDER_MANAGEMENT");
            features.requireFeature(tenant,type==QrEntryType.TABLE?"QR_TABLE_ORDER":"QR_STATIC_ORDER");
            if(type==QrEntryType.TABLE) features.requireFeature(tenant,"TABLE_MANAGEMENT");
        } catch (BusinessException unavailable) { throw unavailable(); }
    }
    private Scope credentialState(UUID tenant,QrEntryType type,UUID tableId,Long epoch,String fingerprint) {
        var restaurant=restaurants.findByIdAndDeletedAtIsNull(tenant).filter(r->r.getStatus()==RestaurantStatus.ACTIVE).orElseThrow(QrScopePolicy::unavailable);
        if(type==QrEntryType.RESTAURANT) {
            if(tableId!=null || epoch!=null || restaurant.getPublicOrderToken()==null
                    || !QrHashes.digest(restaurant.getPublicOrderToken()).equals(fingerprint)) throw unavailable();
            return new Scope(restaurant,null,0);
        }
        if(type!=QrEntryType.TABLE || tableId==null || epoch==null) throw unavailable();
        var table=tables.findByIdAndRestaurantId(tableId,tenant).filter(t->t.getDeletedAt()==null && t.getStatus()==TableStatus.AVAILABLE).orElseThrow(QrScopePolicy::unavailable);
        if(table.getAreaId()!=null) areas.findByIdAndRestaurantId(table.getAreaId(),tenant).filter(a->a.getDeletedAt()==null && a.isActive()).orElseThrow(QrScopePolicy::unavailable);
        long current=currentEpoch(tenant,tableId);
        if(current!=epoch || !QrHashes.digest(table.getQrToken()).equals(fingerprint)) throw unavailable();
        return new Scope(restaurant,table,current);
    }
    public static BusinessException unavailable() {
        return new BusinessException(HttpStatus.FORBIDDEN,"QR_ORDER_UNAVAILABLE","QR ordering is not available.");
    }
    public record Scope(Restaurant restaurant,RestaurantTable table,long epoch) {}
}
