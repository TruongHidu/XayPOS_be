package com.possaas.modules.qrorder.service;

import com.possaas.modules.qrorder.event.QrOrderRequestChanged.Change;
import com.possaas.common.exception.*;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.order.application.port.QrOrderCheckoutQuery;
import com.possaas.modules.order.service.chain.*;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.table.application.port.QrOrderSeatingQuery;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Submission only persists an approval intent; the immutable chain may replay before mutable guards. */
@Service
public class QrGuestSubmissionService {
    private final QrGuestAccessService access;
    private final QrScopePolicy scopes;
    private final RestaurantRepository restaurants;
    private final QrOrderSeatingQuery seating;
    private final QrOrderCheckoutQuery checkout;
    private final QrRequestEventPublisher events;
    private final QrOrderRequestRepository requests;
    private final QrOrderRequestItemRepository items;
    private final QrQuoteService quotes;
    private final QrHashes hashes;
    private final QrRequestMapper mapper;
    private final QrRateLimiter limiter;
    private final AuditService audit;
    private final Clock clock;
    private final Duration ttl;
    private final OrderChain<Context,Result> chain;
    public QrGuestSubmissionService(QrGuestAccessService access,QrScopePolicy scopes,RestaurantRepository restaurants,
            QrOrderSeatingQuery seating,QrOrderCheckoutQuery checkout,QrOrderRequestRepository requests,
            QrOrderRequestItemRepository items,QrQuoteService quotes,QrHashes hashes,QrRequestMapper mapper,
            QrRateLimiter limiter,AuditService audit,Clock clock,QrRequestEventPublisher events,@Value("${app.qr-order.request-ttl-minutes:15}") long minutes) {
        if(minutes<1 || minutes>60) throw new IllegalArgumentException("QR request TTL must be between 1 and 60 minutes");
        this.access=access; this.scopes=scopes; this.restaurants=restaurants; this.seating=seating; this.checkout=checkout;
        this.requests=requests; this.items=items; this.quotes=quotes; this.hashes=hashes; this.mapper=mapper;
        this.limiter=limiter; this.audit=audit; this.clock=clock; this.events=events; ttl=Duration.ofMinutes(minutes);
        chain=new OrderChain<>(List.of(this::scope,this::normalize,this::replay,this::entryGuards,this::quote,this::persist),
                context->{throw new IllegalStateException("QR submission chain did not persist a result");});
    }
    @Transactional
    public Result submit(String token,String key,QrGuestRequests.Submit body,String ip) {
        var grant=access.identify(token);
        limiter.submit(grant.getGuestId(),grant.getEntryType()+":"+Objects.toString(grant.getTableId(),grant.getRestaurantId().toString()));
        restaurants.findByIdForUpdate(grant.getRestaurantId()).orElseThrow(QrScopePolicy::unavailable);
        return chain.execute(new Context(token,key,body,ip,clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
    private Result scope(Context c,OrderNext<Context,Result> next) {
        c.grant=access.require(c.token); c.scope=scopes.validate(c.grant); return next.proceed(c);
    }
    private Result normalize(Context c,OrderNext<Context,Result> next) {
        if(c.key==null || !c.key.matches("[A-Za-z0-9._:-]{1,100}") || c.body==null || c.body.items()==null || c.body.items().isEmpty() || c.body.items().size()>20)
            throw validation();
        c.customerName=text(c.body.customerName(),100); c.customerPhone=text(c.body.customerPhone(),30); c.note=text(c.body.note(),500);
        if(c.grant.getEntryType()==QrEntryType.RESTAURANT && c.customerName==null) throw validation();
        c.lines=new ArrayList<>(); int number=1;
        for(var line:c.body.items()) {
            if(line==null || line.itemId()==null || line.quantity()==null || line.quantity().signum()<=0 || line.quantity().compareTo(new java.math.BigDecimal("20"))>0 || line.quantity().stripTrailingZeros().scale()>3) throw validation();
            var item=new QrOrderRequestItem(); item.initialize(c.grant.getRestaurantId(),c.now); item.setItemId(line.itemId());
            item.setLineNumber(number++); item.setQuantity(line.quantity().setScale(3)); item.setNote(text(line.note(),500)); c.lines.add(item);
        }
        c.payloadHash=hashes.hash(new Intent("qr-submit-v1",c.grant.getGuestId(),c.grant.getEntryType(),c.grant.getRestaurantId(),
                c.grant.getTableId(),c.grant.getTableAccessEpoch(),c.grant.getCredentialHash(),c.customerName,c.customerPhone,c.note,
                c.lines.stream().map(i->new QrGuestRequests.Line(i.getItemId(),i.getQuantity(),i.getNote())).toList()));
        return next.proceed(c);
    }
    private Result replay(Context c,OrderNext<Context,Result> next) {
        var existing=requests.findByRestaurantIdAndIdempotencyKey(c.grant.getRestaurantId(),c.key);
        if(existing.isPresent()) {
            var request=existing.get();
            if(!request.getGuestId().equals(c.grant.getGuestId()) || !request.getPayloadHash().equals(c.payloadHash))
                throw new ConflictException("IDEMPOTENCY_KEY_REUSED","Idempotency key belongs to a different QR intent.");
            return next.complete(new Result(mapper.publicResponse(request,items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(request.getRestaurantId(),request.getId())),true));
        }
        return next.proceed(c);
    }
    private Result entryGuards(Context c,OrderNext<Context,Result> next) {
        if(c.grant.getEntryType()==QrEntryType.TABLE) {
            var open=seating.current(c.grant.getRestaurantId(),c.grant.getTableId());
            c.observedSession=open.map(QrOrderSeatingQuery.CurrentSession::sessionId).orElse(null);
            if(c.observedSession!=null) checkout.requireAccepting(c.grant.getRestaurantId(),c.observedSession);
        }
        return next.proceed(c);
    }
    private Result quote(Context c,OrderNext<Context,Result> next) {
        c.quote=quotes.quote(c.grant.getRestaurantId(),c.scope.restaurant().getCurrencyCode(),c.lines); return next.proceed(c);
    }
    private Result persist(Context c,OrderNext<Context,Result> next) {
        var request=new QrOrderRequest(); request.initialize(c.grant.getRestaurantId(),c.now);
        request.setGuestAuthorizationId(c.grant.getId()); request.setGuestId(c.grant.getGuestId()); request.setEntryType(c.grant.getEntryType());
        request.setTableId(c.grant.getTableId()); request.setTableAccessEpoch(c.grant.getTableAccessEpoch()); request.setCredentialHash(c.grant.getCredentialHash());
        request.setObservedSessionId(c.observedSession); request.setIdempotencyKey(c.key); request.setPayloadHash(c.payloadHash);
        request.setCustomerName(c.customerName); request.setCustomerPhone(c.customerPhone); request.setNote(c.note);
        request.setCurrencyCode(c.quote.currencyCode()); request.setEstimatedAmount(c.quote.amount());
        Instant expires=c.now.plus(ttl); request.setExpiresAt(expires.isAfter(c.grant.getExpiresAt())?c.grant.getExpiresAt():expires);
        requests.saveAndFlush(request);
        for(int n=0;n<c.lines.size();n++) {
            var line=c.lines.get(n); var snapshot=c.quote.lines().get(n); line.setRequestId(request.getId());
            line.setQuotedItemName(snapshot.itemName()); line.setQuotedUnit(snapshot.unit()); line.setQuotedUnitPrice(snapshot.unitPrice()); line.setQuotedLineTotal(snapshot.lineTotal());
        }
        items.saveAllAndFlush(c.lines);
        audit.record(request.getRestaurantId(),null,"QR_ORDER_REQUEST_CREATED","qr_order_requests",request.getId(),null,
                Map.of("requestId",request.getId(),"guestId",request.getGuestId(),"entryType",request.getEntryType().name(),"sourceChannel",request.getEntryType().sourceChannel().name(),"status",request.getStatus().name()),c.ip);
        requests.flush();
        events.publish(request,Change.CREATED);
        return next.complete(new Result(mapper.publicResponse(request,c.lines),false));
    }
    @Transactional(readOnly=true)
    public QrPublicResponse findOwn(String token,UUID requestId) {
        var grant=access.require(token);
        var request=requests.findByIdAndRestaurantId(requestId,grant.getRestaurantId()).filter(r->r.getGuestId().equals(grant.getGuestId()))
                .orElseThrow(()->new ResourceNotFoundException("QR_ORDER_REQUEST_NOT_FOUND","QR request not found."));
        return mapper.publicResponse(request,items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(request.getRestaurantId(),requestId));
    }
    private static BusinessException validation() { return new BusinessException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Invalid QR order request."); }
    private static String text(String value,int max) {
        if(value==null) return null;
        if(value.length()>max) throw validation();
        String normalized=value.trim(); return normalized.isEmpty()?null:normalized;
    }
    public record Result(QrPublicResponse response,boolean replayed) {}
    private record Intent(String format,UUID guestId,QrEntryType entryType,UUID restaurantId,UUID tableId,Long epoch,
            String credentialHash,String customerName,String customerPhone,String note,List<QrGuestRequests.Line> items) {}
    private static final class Context {
        final String token,key,ip; final QrGuestRequests.Submit body; final Instant now;
        QrGuestAuthorization grant; QrScopePolicy.Scope scope; String payloadHash,customerName,customerPhone,note; UUID observedSession;
        List<QrOrderRequestItem> lines; QrQuoteService.QrQuote quote;
        Context(String token,String key,QrGuestRequests.Submit body,String ip,Instant now) { this.token=token; this.key=key; this.body=body; this.ip=ip; this.now=now; }
    }
}
