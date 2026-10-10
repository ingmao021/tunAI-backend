package com.tunai.api.domain.exception;

/** El proveedor rechazó la imagen (4xx, por ejemplo, moderación; flujo A6). Se traduce a {@code 422 PROVIDER_REJECTED}. */
public class ProviderRejectedException extends DomainException {

    public ProviderRejectedException(String message) {
        super(message);
    }
}
