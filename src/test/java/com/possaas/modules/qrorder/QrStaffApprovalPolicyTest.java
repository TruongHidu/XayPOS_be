package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.order.application.port.QrOrderCheckoutQuery;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.OrderItemRepository;
import com.possaas.modules.order.service.*;
import com.possaas.modules.order.service.chain.OrderChain;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.qrorder.service.*;
import com.possaas.modules.qrorder.service.chain.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.table.application.port.QrOrderSeatingQuery;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class QrStaffApprovalPolicyTest {
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(), table = UUID.randomUUID(), session = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-14T01:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CurrentUser staff = new CurrentUser(user, tenant, "private@example.test", "CASHIER",
        Set.of("ORDER_VIEW", "ORDER_CREATE", "ORDER_UPDATE", "TABLE_OPEN"));

    @Test void approveRequiresExplicitTargetWhileAllowingExplicitNullAndRejectingUnsupportedFields() throws Exception {
        var missing = json.readValue("{\"expectedRequestVersion\":0,\"expectedReviewContextHash\":\"" + "a".repeat(64) + "\"}", QrStaffRequests.Approve.class);
        var explicitNull = json.readValue("{\"expectedRequestVersion\":0,\"expectedReviewContextHash\":\"" + "a".repeat(64) + "\",\"targetSessionId\":null}", QrStaffRequests.Approve.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(missing)).hasSize(1);
            assertThat(factory.getValidator().validate(explicitNull)).isEmpty();
        }
        assertThat(missing.isTargetSessionPresent()).isFalse(); assertThat(explicitNull.isTargetSessionPresent()).isTrue();
        assertThatThrownBy(() -> json.readValue("{\"actor\":\"spoofed\"}", QrStaffRequests.Approve.class)).hasMessageContaining("Unsupported approval field");
    }

    @Test void targetsCannotSilentlyAttachToAChangedSeatingButCanChooseANewlyReviewedSession() {
        var policy = new QrApprovalTargetPolicy(); var request = request(QrEntryType.TABLE);
        policy.require(request, null, review(null));
        assertCode(() -> policy.require(request, null, review(session)), "QR_REQUEST_SESSION_CHANGED");
        policy.require(request, session, review(session));
        request.setObservedSessionId(session);
        assertCode(() -> policy.require(request, UUID.randomUUID(), review(UUID.randomUUID())), "QR_REQUEST_SESSION_CHANGED");
        assertCode(() -> policy.require(request, null, review(null)), "QR_REQUEST_SESSION_CHANGED");
    }

    @Test void restaurantTargetNeverRequiresTableContext() {
        var policy = new QrApprovalTargetPolicy(); var request = request(QrEntryType.RESTAURANT);
        policy.require(request, null, new QrReviewContextService.Review(null, null, true, null, null));
        assertCode(() -> policy.require(request, session, null), "TAKEAWAY_SESSION_NOT_ALLOWED");
    }

    @Test void reviewOfTakeawayDoesNotCallSeatingServingCheckoutAndHashesOnlyItsOwnQuote() {
        var scopes = mock(QrScopePolicy.class); var quotes = mock(QrQuoteService.class);
        var seating = mock(QrOrderSeatingQuery.class); var serving = mock(OrderSessionServingPolicy.class); var checkout = mock(QrOrderCheckoutQuery.class);
        var request = request(QrEntryType.RESTAURANT); var restaurant = restaurant();
        when(scopes.validate(request)).thenReturn(new QrScopePolicy.Scope(restaurant, null, 0));
        when(quotes.quote(tenant, "VND", List.of())).thenReturn(quote("30.00"));
        var service = new QrReviewContextService(scopes, quotes, seating, serving, checkout, new QrHashes(json));
        var first = service.require(request, List.of(), staff, now);
        var repeated = service.require(request, List.of(), staff, now.plusSeconds(1));
        assertThat(first.hash()).isEqualTo(repeated.hash()); assertThat(first.context()).isNull(); assertThat(first.canApprove()).isTrue();
        when(quotes.quote(tenant, "VND", List.of())).thenReturn(quote("31.00"));
        assertThat(service.require(request, List.of(), staff, now).hash()).isNotEqualTo(first.hash());
        verifyNoInteractions(seating, serving, checkout);
    }

    @Test void tableReviewHashIncludesActualServingVersionAndRejectsDraftWithoutMutatingIt() {
        var scopes = mock(QrScopePolicy.class); var quotes = mock(QrQuoteService.class);
        var seating = mock(QrOrderSeatingQuery.class); var serving = mock(OrderSessionServingPolicy.class); var checkout = mock(QrOrderCheckoutQuery.class);
        var request = request(QrEntryType.TABLE); var root = order(); root.setStatus(OrderStatus.CONFIRMED);
        when(scopes.validate(request)).thenReturn(new QrScopePolicy.Scope(restaurant(), null, 0));
        when(quotes.quote(tenant, "VND", List.of())).thenReturn(quote("30.00"));
        when(seating.current(tenant, table)).thenReturn(Optional.of(new QrOrderSeatingQuery.CurrentSession(session, (short)2, 0)));
        when(serving.findServing(tenant, session)).thenReturn(Optional.of(root));
        var service = new QrReviewContextService(scopes, quotes, seating, serving, checkout, new QrHashes(json));
        var first = service.require(request, List.of(), staff, now);
        when(seating.current(tenant, table)).thenReturn(Optional.of(new QrOrderSeatingQuery.CurrentSession(session, (short)3, 1)));
        assertThat(service.require(request, List.of(), staff, now).hash()).isNotEqualTo(first.hash());
        when(seating.current(tenant, table)).thenReturn(Optional.of(new QrOrderSeatingQuery.CurrentSession(session, (short)2, 0)));
        root.setVersion(1);
        assertThat(service.require(request, List.of(), staff, now).hash()).isNotEqualTo(first.hash());
        root.setStatus(OrderStatus.OPEN);
        assertThat(service.inspect(request, List.of(), staff, now).reasonCode()).isEqualTo("QR_ORDER_DRAFT_NOT_SUBMITTED");
        assertThat(root.getMutationSequence()).isZero(); assertThat(root.getStatus()).isEqualTo(OrderStatus.OPEN);
    }

    @Test void missingTableOpenOnlyMakesUnoccupiedReviewUnapprovableAndDoesNotHideRequest() {
        var scopes = mock(QrScopePolicy.class); var quotes = mock(QrQuoteService.class); var seating = mock(QrOrderSeatingQuery.class);
        var request = request(QrEntryType.TABLE);
        when(scopes.validate(request)).thenReturn(new QrScopePolicy.Scope(restaurant(), null, 0));
        when(quotes.quote(tenant, "VND", List.of())).thenReturn(quote("30.00"));
        when(seating.current(tenant, table)).thenReturn(Optional.empty());
        var service = new QrReviewContextService(scopes, quotes, seating, mock(OrderSessionServingPolicy.class), mock(QrOrderCheckoutQuery.class), new QrHashes(json));
        var actor = new CurrentUser(user, tenant, "private@example.test", "OWNER", Set.of("ORDER_VIEW", "ORDER_CREATE", "ORDER_UPDATE"));
        var decision = service.inspect(request, List.of(), actor, now);
        assertThat(decision.canApprove()).isFalse(); assertThat(decision.reasonCode()).isEqualTo("FORBIDDEN");
        assertThat(request.getStatus()).isEqualTo(QrRequestStatus.PENDING);
    }

    @Test void expiredReviewStopsBeforeScopeAndPriceLookups() {
        var request = request(QrEntryType.RESTAURANT); request.setExpiresAt(now);
        var scopes = mock(QrScopePolicy.class); var quotes = mock(QrQuoteService.class);
        var service = new QrReviewContextService(scopes, quotes, mock(QrOrderSeatingQuery.class), mock(OrderSessionServingPolicy.class), mock(QrOrderCheckoutQuery.class), new QrHashes(json));
        assertThat(service.inspect(request, List.of(), staff, now).reasonCode()).isEqualTo("QR_ORDER_REQUEST_EXPIRED");
        verifyNoInteractions(scopes, quotes);
    }

    @Test void approvedReplayBypassesVersionQuoteExpiryAndMutablePaymentOrSessionState() {
        var request = request(QrEntryType.TABLE); request.setStatus(QrRequestStatus.APPROVED); request.setVersion(3);
        request.setApprovedOrderId(UUID.randomUUID()); request.setApprovedSessionId(session); request.setApprovedAt(now); request.setApprovedBy(user);
        request.setApprovedAmount(BigDecimal.TEN); request.setExpiresAt(now.minusSeconds(1));
        var root = order(); root.setId(request.getApprovedOrderId()); root.setStatus(OrderStatus.COMPLETED); root.setPaymentStatus(OrderPaymentStatus.PAID);
        var access = mock(OrderAccessService.class); var items = mock(OrderItemRepository.class);
        when(access.order(tenant, request.getApprovedOrderId())).thenReturn(root);
        when(items.findAllByRestaurantIdAndOrderIdOrderByLineNumberAsc(tenant, root.getId())).thenReturn(List.of());
        var replay = new QrApprovalReplayHandler(access, items, new OrderResponseMapper(), new QrStaffResponseMapper());
        var context = new QrApprovalContext(new OrderAccessService.Locked(staff, restaurant()), now, "127.0.0.1", approve(null), request, List.of());
        var chain = new OrderChain<QrApprovalContext,QrApprovalResponse>(List.of(replay, (c,n) -> { throw new AssertionError("Replay reached mutable validation"); }),
            c -> { throw new AssertionError("Replay reached persistence"); });
        var result = chain.execute(context);
        assertThat(result.replay()).isTrue(); assertThat(result.approvedAt()).isEqualTo(now); assertThat(result.version()).isEqualTo(3);
        assertThat(result.order().status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test void replayWrongTargetAndTakeawayNonNullTargetFailBeforeRootReads() {
        var access = mock(OrderAccessService.class); var items = mock(OrderItemRepository.class);
        var replay = new QrApprovalReplayHandler(access, items, new OrderResponseMapper(), new QrStaffResponseMapper());
        var request = request(QrEntryType.TABLE); request.setStatus(QrRequestStatus.APPROVED); request.setApprovedSessionId(session);
        var chain = new OrderChain<QrApprovalContext,QrApprovalResponse>(List.of(replay), c -> { throw new AssertionError(); });
        var context = new QrApprovalContext(new OrderAccessService.Locked(staff, restaurant()), now, null, approve(UUID.randomUUID()), request, List.of());
        assertCode(() -> chain.execute(context), "QR_REQUEST_SESSION_CHANGED");
        request.setEntryType(QrEntryType.RESTAURANT);
        assertCode(() -> chain.execute(context), "TAKEAWAY_SESSION_NOT_ALLOWED"); verifyNoInteractions(access, items);
    }

    @Test void stalePendingVersionNeverReachesApprovalPreparation() {
        var request = request(QrEntryType.RESTAURANT); request.setVersion(1);
        var access = mock(OrderAccessService.class); var items = mock(OrderItemRepository.class);
        var replay = new QrApprovalReplayHandler(access, items, new OrderResponseMapper(), new QrStaffResponseMapper());
        var chain = new OrderChain<QrApprovalContext,QrApprovalResponse>(List.of(replay), c -> { throw new AssertionError(); });
        var context = new QrApprovalContext(new OrderAccessService.Locked(staff, restaurant()), now, null, approve(null), request, List.of());
        assertCode(() -> chain.execute(context), "CONCURRENT_QR_REQUEST_UPDATE"); verifyNoInteractions(access, items);
    }

    @Test void rejectedRetryKeepsOriginalReviewerReasonAndDoesNotWriteOrAudit() {
        var request = request(QrEntryType.RESTAURANT); request.setStatus(QrRequestStatus.REJECTED); request.setRejectedBy(user);
        request.setRejectedAt(now.minusSeconds(10)); request.setRejectionReason("Original reason");
        var access = mock(QrStaffAccessService.class); var requests = mock(QrOrderRequestRepository.class); var items = mock(QrOrderRequestItemRepository.class);
        var audit = mock(QrRequestAudit.class);
        when(access.lockReject()).thenReturn(new OrderAccessService.Locked(staff, restaurant()));
        when(access.request(tenant, request.getId())).thenReturn(request);
        when(items.findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(tenant, request.getId())).thenReturn(List.of());
        var service = new QrStaffDecisionService(access, requests, items, null, null, null, null, audit, new QrStaffResponseMapper(), Clock.fixed(now, ZoneOffset.UTC));
        var result = service.reject(request.getId(), new QrStaffRequests.Reject(999L, "Different reason"), null);
        assertThat(result.rejectionReason()).isEqualTo("Original reason"); assertThat(result.rejectedAt()).isEqualTo(now.minusSeconds(10));
        verifyNoInteractions(requests, audit);
    }

    @Test void dtoToStringDoesNotContainCustomerOrApprovalIntentSecrets() {
        var request = request(QrEntryType.RESTAURANT); request.setCustomerName("private-name"); request.setCustomerPhone("private-phone"); request.setNote("private-note");
        var response = new QrStaffResponseMapper().summary(request, List.of(), now);
        assertThat(response.toString()).doesNotContain("private-name", "private-phone", "private-note");
        assertThat(new QrStaffRequests.Reject(1L, "private-note").toString()).doesNotContain("private-note");
    }

    @Test @SuppressWarnings("unchecked") void listLoadsAllRequestItemsInOneBatchWithoutDetailOrScopeLookups() {
        var first = request(QrEntryType.TABLE); var second = request(QrEntryType.RESTAURANT);
        var access = mock(QrStaffAccessService.class); var requests = mock(QrOrderRequestRepository.class);
        var items = mock(QrOrderRequestItemRepository.class); var review = mock(QrReviewContextService.class);
        when(access.read()).thenReturn(staff);
        when(requests.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(first, second)));
        when(items.findAllByRestaurantIdAndRequestIdInOrderByLineNumberAsc(tenant, List.of(first.getId(), second.getId()))).thenReturn(List.of());
        var service = new QrStaffQueryService(access, requests, items, new QrStaffResponseMapper(), review, Clock.fixed(now, ZoneOffset.UTC));
        assertThat(service.search(new QrStaffSearch()).content()).hasSize(2);
        verify(items).findAllByRestaurantIdAndRequestIdInOrderByLineNumberAsc(tenant, List.of(first.getId(), second.getId()));
        verify(items, never()).findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(any(), any()); verifyNoInteractions(review);
    }

    private QrOrderRequest request(QrEntryType type) {
        var r = new QrOrderRequest(); r.initialize(tenant, now); r.setId(UUID.randomUUID()); r.setGuestId(UUID.randomUUID());
        r.setEntryType(type); r.setCurrencyCode("VND"); r.setExpiresAt(now.plusSeconds(900)); r.setEstimatedAmount(BigDecimal.TEN);
        r.setPayloadHash("b".repeat(64)); r.setCredentialHash("c".repeat(64));
        if (type == QrEntryType.TABLE) { r.setTableId(table); r.setTableAccessEpoch(0L); }
        return r;
    }
    private QrReviewContextService.Review review(UUID current) {
        return new QrReviewContextService.Review(null, null, true, null, new QrStaffRequestDetail.ReviewContext(current, 0L, null, null, null));
    }
    private QrQuoteService.QrQuote quote(String amount) { return new QrQuoteService.QrQuote("VND", new BigDecimal(amount), List.of()); }
    private Restaurant restaurant() { var r = new Restaurant(); r.setId(tenant); r.setCurrencyCode("VND"); return r; }
    private Order order() {
        var o = new Order(); o.initialize(tenant, now); o.setId(UUID.randomUUID()); o.setOrderCode("OD-Qr-test");
        o.setServiceType(ServiceType.DINE_IN); o.setSourceChannel(SourceChannel.WAITER); o.setTableSessionId(session); o.setCurrencyCode("VND"); return o;
    }
    private QrStaffRequests.Approve approve(UUID target) {
        var body = new QrStaffRequests.Approve(); body.setExpectedRequestVersion(0L); body.setExpectedReviewContextHash("a".repeat(64)); body.setTargetSessionId(target); return body;
    }
    private void assertCode(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}
