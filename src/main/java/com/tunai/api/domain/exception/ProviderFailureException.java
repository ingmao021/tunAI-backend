package com.tunai.api.domain.exception;

/** El proveedor de IA falló (5xx o respuesta no-GLB). Se traduce a {@code 502 PROVIDER_ERROR} y libera la cuota. */
public class ProviderFailureException extends DomainException {

    public ProviderFailureException(String message) {
        super(message);
    }

    public ProviderFailureException(String message, Throwable cause) {
        super(message, null, cause);
    }
}
