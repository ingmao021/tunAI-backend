package com.tunai.api.domain.exception;

import java.time.Duration;

/**
 * El proveedor no está disponible para nosotros: clave inválida, sin créditos o límite de tasa (flujo A9). Se traduce a
 * {@code 503 SERVICE_UNAVAILABLE} y se registra como ERROR para el operador.
 */
public class ProviderUnavailableException extends DomainException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }
}
