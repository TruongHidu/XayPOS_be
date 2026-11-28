package com.possaas.common.captcha;

/**
 * Checks a CAPTCHA token issued to the browser. Implementations never throw for a bad or missing token or for a
 * provider outage: they return a {@link CaptchaResult} and the caller decides the HTTP answer.
 */
public interface CaptchaVerifier {
    /**
     * @param token          the token the browser sent, possibly null or blank
     * @param clientIp       the caller's address, passed to the provider as a hint
     * @param expectedAction the action the widget was rendered with for this endpoint
     */
    CaptchaResult verify(String token, String clientIp, String expectedAction);
}
