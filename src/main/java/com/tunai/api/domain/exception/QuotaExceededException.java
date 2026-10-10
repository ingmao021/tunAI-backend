package com.tunai.api.domain.exception;

import java.time.Duration;

/** Cuota diaria por instalación o por IP agotada (RN-02, RN-10). Se traduce a {@code 429 QUOTA_EXCEEDED}. */
public class QuotaExceededException extends DomainException {

    public QuotaExceededException(String message, Duration retryAfter) {
        super(message, retryAfter);
    }
}
