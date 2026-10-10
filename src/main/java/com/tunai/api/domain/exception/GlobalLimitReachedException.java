package com.tunai.api.domain.exception;

import java.time.Duration;

/**
 * Límite global diario agotado (RN-11). Se traduce a {@code 503 SERVICE_UNAVAILABLE} sin revelar detalles del
 * presupuesto (flujo A5).
 */
public class GlobalLimitReachedException extends DomainException {

    public GlobalLimitReachedException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }
}
