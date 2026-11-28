package com.possaas.common.ratelimit;

import java.util.List;

/**
 * Contributed by a module to rate-limit its own endpoints per client address. The request filter applies every
 * contributed rule, so adding a limited endpoint never requires changing the filter.
 */
public interface RequestRateLimitRules {
    List<RequestRateLimitRule> rules();
}
