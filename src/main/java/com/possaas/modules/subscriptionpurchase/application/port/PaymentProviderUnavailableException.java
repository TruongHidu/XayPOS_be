package com.possaas.modules.subscriptionpurchase.application.port;

public class PaymentProviderUnavailableException extends RuntimeException {
    public PaymentProviderUnavailableException(String message) { super(message); }
    public PaymentProviderUnavailableException(String message, Throwable cause) { super(message, cause); }
}
