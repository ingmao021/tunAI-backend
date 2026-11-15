package com.tunai.api.domain.exception;

import com.tunai.api.domain.model.QuotaStatus;
import java.util.Objects;

/**
 * La instalación no tiene créditos para otro modelo (RN-02). Los créditos no se renuevan solos, así que no lleva
 * {@code Retry-After}; se traduce a {@code 402 INSUFFICIENT_CREDITS} con los headers de cuota y el cliente ofrece los
 * planes.
 */
public class InsufficientCreditsException extends DomainException {

    private final QuotaStatus status;

    public InsufficientCreditsException(String message, QuotaStatus status) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
    }

    public QuotaStatus status() {
        return status;
    }
}
