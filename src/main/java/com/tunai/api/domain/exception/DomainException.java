package com.tunai.api.domain.exception;

import java.time.Duration;
import java.util.Optional;

/**
 * Base de los errores de negocio. Cada subclase corresponde a un código del catálogo (§15.4); la traducción a HTTP la
 * hace el adaptador web.
 */
public abstract class DomainException extends RuntimeException {

    private final Duration retryAfter;

    protected DomainException(String message) {
        this(message, null, null);
    }

    protected DomainException(String message, Duration retryAfter) {
        this(message, retryAfter, null);
    }

    protected DomainException(String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Si el proveedor pudo haber cobrado la generación aunque el cliente no reciba el modelo, por ejemplo, porque la
     * tarea ya se creó y se agotó el tiempo esperándola. En ese caso la cuota diaria (IP y global) se descuenta igual,
     * para que el límite siga protegiendo el saldo del proveedor (§13.5).
     */
    public boolean providerMayHaveCharged() {
        return false;
    }

    /** Tiempo sugerido antes de reintentar, para el header {@code Retry-After} (RF-BE-015). */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
