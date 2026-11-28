package com.possaas.modules.auth.controller;

import com.possaas.common.captcha.CaptchaProtectedRequest;
import com.possaas.common.captcha.CaptchaProtectedRequests;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/** Restaurant registration requires a human: the web renders the Turnstile widget with the same action. */
@Component
public class AuthCaptchaRequests implements CaptchaProtectedRequests {
    static final String REGISTER_ACTION = "register";

    private static final List<CaptchaProtectedRequest> REQUESTS = List.of(
        new CaptchaProtectedRequest(HttpMethod.POST, AuthRateLimitRules.REGISTER, REGISTER_ACTION)
    );

    @Override
    public List<CaptchaProtectedRequest> requests() {
        return REQUESTS;
    }
}
