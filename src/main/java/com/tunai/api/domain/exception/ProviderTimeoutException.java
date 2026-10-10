package com.tunai.api.domain.exception;

/** Tiempo agotado con el proveedor (flujo A8). Se traduce a {@code 504 PROVIDER_TIMEOUT}. */
public class ProviderTimeoutException extends DomainException {

    public ProviderTimeoutException(String message, Throwable cause) {
        super(message, null, cause);
    }
}
