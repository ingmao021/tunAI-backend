package com.tunai.api.domain.exception;

/**
 * Tiempo agotado con el proveedor (flujo A8). Se traduce a {@code 504 PROVIDER_TIMEOUT}. Si la tarea ya se había
 * creado, el proveedor puede terminarla y cobrarla ({@link #providerMayHaveCharged()}).
 */
public class ProviderTimeoutException extends DomainException {

    private final boolean providerMayHaveCharged;

    public ProviderTimeoutException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ProviderTimeoutException(String message, Throwable cause, boolean providerMayHaveCharged) {
        super(message, null, cause);
        this.providerMayHaveCharged = providerMayHaveCharged;
    }

    @Override
    public boolean providerMayHaveCharged() {
        return providerMayHaveCharged;
    }
}
