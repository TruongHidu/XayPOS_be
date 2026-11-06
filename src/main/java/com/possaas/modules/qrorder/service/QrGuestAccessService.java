package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.*;
import com.possaas.common.security.PublicLinkTokenGenerator;
import com.possaas.modules.qrorder.dto.QrGuestAccessResponse;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.QrGuestAuthorizationRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.restaurant.service.PublicRestaurantMenuResolver;
import com.possaas.modules.table.service.PublicTableQrResolver;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QrGuestAccessService {
    private final QrGuestAuthorizationRepository grants;
    private final PublicTableQrResolver tableResolver;
    private final PublicRestaurantMenuResolver restaurantResolver;
    private final RestaurantRepository restaurants;
    private final PublicLinkTokenGenerator tokens;
    private final QrScopePolicy scopes;
    private final QrRateLimiter limiter;
    private final Clock clock;
    private final Duration ttl;
    public QrGuestAccessService(QrGuestAuthorizationRepository grants,PublicTableQrResolver tableResolver,
            PublicRestaurantMenuResolver restaurantResolver,RestaurantRepository restaurants,
            PublicLinkTokenGenerator tokens,QrScopePolicy scopes,QrRateLimiter limiter,Clock clock,
            @Value("${app.qr-order.guest-access-ttl-minutes:60}") long minutes) {
        if(minutes<1 || minutes>1440) throw new IllegalArgumentException("QR access TTL must be between 1 and 1440 minutes");
        this.grants=grants; this.tableResolver=tableResolver; this.restaurantResolver=restaurantResolver;
        this.restaurants=restaurants; this.tokens=tokens; this.scopes=scopes; this.limiter=limiter; this.clock=clock; ttl=Duration.ofMinutes(minutes);
    }
    @Transactional
    public QrGuestAccessResponse mintTable(String qrToken,String ip) {
        limiter.mint(ip,"TABLE:"+qrToken);
        var resolved=tableResolver.resolve(qrToken);
        restaurants.findByIdForUpdate(resolved.restaurantId()).orElseThrow(QrScopePolicy::unavailable);
        // Re-resolve after restaurant lock: rotation/lifecycle operations share this same lock.
        var current=tableResolver.resolve(qrToken);
        return mint(current.restaurantId(),QrEntryType.TABLE,current.tableId(),QrHashes.digest(qrToken));
    }
    @Transactional
    public QrGuestAccessResponse mintRestaurant(String menuToken,String ip) {
        limiter.mint(ip,"RESTAURANT:"+menuToken);
        var resolved=restaurantResolver.resolve(menuToken);
        restaurants.findByIdForUpdate(resolved.restaurantId()).orElseThrow(QrScopePolicy::unavailable);
        var current=restaurantResolver.resolve(menuToken);
        return mint(current.restaurantId(),QrEntryType.RESTAURANT,null,QrHashes.digest(menuToken));
    }
    private QrGuestAccessResponse mint(UUID tenant,QrEntryType entry,UUID tableId,String credentialHash) {
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS); String token=tokens.generate();
        var grant=new QrGuestAuthorization(); grant.initialize(tenant,now); grant.setGuestId(UUID.randomUUID());
        grant.setEntryType(entry); grant.setTableId(tableId); grant.setCredentialHash(credentialHash);
        grant.setTableAccessEpoch(entry==QrEntryType.TABLE?scopes.currentEpoch(tenant,tableId):null);
        grant.setTokenHash(QrHashes.digest(token)); grant.setExpiresAt(now.plus(ttl));
        var scope=scopes.validate(grant); grants.saveAndFlush(grant);
        var restaurant=scope.restaurant(); var table=scope.table();
        return new QrGuestAccessResponse(token,grant.getExpiresAt(),entry,entry.serviceType(),entry.sourceChannel(),
                new QrGuestAccessResponse.RestaurantMetadata(restaurant.getId(),restaurant.getName(),restaurant.getCurrencyCode()),
                table==null?null:new QrGuestAccessResponse.TableMetadata(table.getId(),table.getCode(),table.getName()));
    }
    @Transactional(readOnly=true)
    public QrGuestAuthorization require(String token) {
        var grant=identify(token); scopes.validate(grant); return grant;
    }
    /** Minimal authenticated identity lookup lets submission lock before mutable scope revalidation. */
    public QrGuestAuthorization identify(String token) {
        if(!PublicLinkTokenGenerator.isValid(token)) throw invalid();
        return grants.findByTokenHash(QrHashes.digest(token)).filter(g->g.getRevokedAt()==null && g.getExpiresAt().isAfter(clock.instant())).orElseThrow(QrGuestAccessService::invalid);
    }
    private static BusinessException invalid() {
        return new BusinessException(HttpStatus.UNAUTHORIZED,"QR_GUEST_ACCESS_INVALID","Guest access is invalid or expired.");
    }
}
