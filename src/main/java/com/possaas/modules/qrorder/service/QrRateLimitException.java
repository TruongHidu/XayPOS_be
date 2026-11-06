package com.possaas.modules.qrorder.service;

import com.possaas.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public final class QrRateLimitException extends BusinessException {
    public QrRateLimitException() { super(HttpStatus.TOO_MANY_REQUESTS,"QR_RATE_LIMIT_EXCEEDED","Too many QR requests. Please try again later."); }
}
