package com.tunai.api.domain.exception;

/**
 * Tiempo agotado con el proveedor (flujo A8). Se traduce a {@code 504 PROVIDER_TIMEOUT}. Si la tarea ya se había
 * creado, el proveedor puede terminarla y cobrarla ({@link #providerMayHaveCharged()}). Si además la generación sigue
 * en curso en el servidor ({@link #generationContinues()}), reintentar con la misma foto la retoma sin otro cobro.
 */
public class ProviderTimeoutException extends DomainException {

    private final boolean providerMayHaveCharged;
    private final boolean generationContinues;

    public ProviderTimeoutException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ProviderTimeoutException(String message, Throwable cause, boolean providerMayHaveCharged) {
        this(message, cause, providerMayHaveCharged, false);
    }

    public ProviderTimeoutException(
            String message, Throwable cause, boolean providerMayHaveCharged, boolean generationContinues) {
        super(message, null, cause);
        this.providerMayHaveCharged = providerMayHaveCharged;
        this.generationContinues = generationContinues;
    }

    @Override
    public boolean providerMayHaveCharged() {
        return providerMayHaveCharged;
    }

    /** {@code true} si la generación sigue en el servidor y un reintento con la misma foto la retoma. */
    public boolean generationContinues() {
        return generationContinues;
    }
}
