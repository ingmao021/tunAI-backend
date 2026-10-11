package com.tunai.api.domain.exception;

import com.tunai.api.domain.model.QuotaStatus;
import java.time.Duration;
import java.util.Objects;

/**
 * Cuota diaria por IP agotada (RN-10). Se traduce a {@code 429 QUOTA_EXCEEDED} con los headers
 * de cuota (flujo A4).
 */
public class QuotaExceededException extends DomainException {

    private final QuotaStatus status;

    public QuotaExceededException(String message, QuotaStatus status, Duration retryAfter) {
        super(message, retryAfter);
        this.status = Objects.requireNonNull(status, "status");
    }

    public QuotaStatus status() {
        return status;
    }
}
