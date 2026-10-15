package com.tunai.api.domain.exception;

/**
 * El proveedor de IA falló (5xx, tarea fallida o respuesta no-GLB). Se traduce a {@code 502 PROVIDER_ERROR}. Si
 * {@link #providerMayHaveCharged()} es falso se libera toda la reserva de cuota; si es verdadero, solo los créditos de
 * la instalación.
 */
public class ProviderFailureException extends DomainException {

    private final boolean providerMayHaveCharged;

    public ProviderFailureException(String message) {
        this(message, null, false);
    }

    public ProviderFailureException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ProviderFailureException(String message, Throwable cause, boolean providerMayHaveCharged) {
        super(message, null, cause);
        this.providerMayHaveCharged = providerMayHaveCharged;
    }

    @Override
    public boolean providerMayHaveCharged() {
        return providerMayHaveCharged;
    }
}
