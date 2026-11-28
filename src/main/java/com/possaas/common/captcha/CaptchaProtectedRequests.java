package com.possaas.common.captcha;

import java.util.List;

/**
 * Contributed by a module to require a CAPTCHA on its own endpoints. The verification filter applies every
 * contributed request, so protecting another endpoint never requires changing the filter.
 */
public interface CaptchaProtectedRequests {
    List<CaptchaProtectedRequest> requests();
}
