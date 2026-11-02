package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.service.PackageFeatureCatalog;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseQuote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PurchaseQuoteFactory {
    private final PackagePlanRepository packages;
    private final PackageFeatureCatalog features;

    @Transactional(propagation = Propagation.MANDATORY)
    public PurchaseQuote capture(String code, Instant now) {
        var plan = packages.findByCodeForUpdate(code).filter(p -> p.isActive())
            .orElseThrow(() -> new ResourceNotFoundException("PACKAGE_NOT_FOUND", "Active package not found"));
        BigDecimal price = plan.getPriceAmount();
        if (!"VND".equals(plan.getCurrencyCode()) || price == null || price.signum() <= 0
            || price.compareTo(new BigDecimal("999999999999")) > 0 || price.stripTrailingZeros().scale() > 0
            || plan.getBillingCycleMonths() <= 0 || (plan.getMaxStaff() != null && plan.getMaxStaff() <= 0))
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "PACKAGE_NOT_PURCHASABLE", "Package requires a positive whole VND price and valid limits");
        var grants = features.getActiveFeatures(plan.getId());
        if (grants.stream().anyMatch(g -> g.limits().containsKey("maxStaff")))
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "PACKAGE_NOT_PURCHASABLE", "Staff limit must be configured at package level");
        return new PurchaseQuote(1, plan.getId(), plan.getCode(), plan.getName(), price.longValueExact(), "VND",
            plan.getBillingCycleMonths(), grants, plan.getMaxStaff(), now, now.plus(Duration.ofMinutes(15)), "Asia/Ho_Chi_Minh");
    }
}
