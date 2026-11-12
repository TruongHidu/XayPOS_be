package com.possaas.modules.subscriptionpurchase.controller;

import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.subscription.dto.PageResponse;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.AcceptEventRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.CloseEventRequest;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.PaymentEventSummary;
import com.possaas.modules.subscriptionpurchase.dto.AdminPaymentDtos.ReviewCounts;
import com.possaas.modules.subscriptionpurchase.entity.PaymentEventState;
import com.possaas.modules.subscriptionpurchase.entity.ReviewReason;
import com.possaas.modules.subscriptionpurchase.service.AdminPaymentEventQueryService;
import com.possaas.modules.subscriptionpurchase.service.AdminPaymentEventReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Super admin: received bank transfers and the manual review queue. */
@Validated
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("@adminSecurity.isSystemSuperAdmin(authentication) and hasAuthority('SUBSCRIPTION_PAYMENT_REVIEW')")
public class AdminPaymentEventController {
    private final AdminPaymentEventQueryService queries;
    private final AdminPaymentEventReviewService reviews;
    private final CurrentUserProvider currentUser;

    @GetMapping("/payment-events")
    ResponseEntity<PageResponse<PaymentEventSummary>> search(
        @RequestParam(required = false) PaymentEventState state,
        @RequestParam(required = false) ReviewReason reviewReason,
        @RequestParam(required = false) UUID restaurantId,
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return noStore(queries.search(state, reviewReason, restaurantId, q, page, size));
    }

    @GetMapping("/payment-events/{eventId}")
    ResponseEntity<PaymentEventSummary> detail(@PathVariable UUID eventId) {
        return noStore(queries.detail(eventId));
    }

    @PostMapping("/payment-events/{eventId}/close")
    ResponseEntity<PaymentEventSummary> close(@PathVariable UUID eventId, @Valid @RequestBody CloseEventRequest request,
            HttpServletRequest http) {
        return noStore(reviews.close(eventId, request, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @PostMapping("/payment-events/{eventId}/retry")
    ResponseEntity<PaymentEventSummary> retry(@PathVariable UUID eventId, HttpServletRequest http) {
        return noStore(reviews.retry(eventId, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @PostMapping("/payment-events/{eventId}/accept")
    ResponseEntity<PaymentEventSummary> accept(@PathVariable UUID eventId, @Valid @RequestBody AcceptEventRequest request,
            HttpServletRequest http) {
        return noStore(reviews.accept(eventId, request, currentUser.getRequired().userId(), http.getRemoteAddr()));
    }

    @GetMapping("/payment-reviews/counts")
    ResponseEntity<ReviewCounts> reviewCounts() {
        return noStore(queries.reviewCounts());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
