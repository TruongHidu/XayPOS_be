package com.possaas.infrastructure.captcha;

import com.possaas.common.captcha.CaptchaResult;
import com.possaas.common.captcha.CaptchaVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Accepts every request. Active unless {@code app.captcha.enabled=true} (dev without keys, tests). */
@Component
@ConditionalOnProperty(prefix = "app.captcha", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledCaptchaVerifier implements CaptchaVerifier {
    @Override
    public CaptchaResult verify(String token, String clientIp, String expectedAction) {
        return CaptchaResult.passed();
    }
}
