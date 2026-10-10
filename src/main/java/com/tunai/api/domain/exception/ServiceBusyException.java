package com.tunai.api.domain.exception;

import java.time.Duration;

/** Sin cupo de concurrencia hacia el proveedor (RF-BE-011). Se traduce a {@code 503 SERVICE_BUSY}. */
public class ServiceBusyException extends DomainException {

    public ServiceBusyException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }
}
