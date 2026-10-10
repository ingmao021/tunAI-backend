package com.tunai.api.adapter.out.concurrency;

import com.tunai.api.application.port.out.ConcurrencyLimiterPort;
import com.tunai.api.domain.exception.ServiceBusyException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Limita las generaciones simultáneas con un semáforo, sin esperar: si no hay cupo responde {@code 503 SERVICE_BUSY}. */
public final class SemaphoreConcurrencyLimiter implements ConcurrencyLimiterPort {

    /** {@code Retry-After} de {@code SERVICE_BUSY} (RF-BE-015). */
    static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final Semaphore semaphore;

    public SemaphoreConcurrencyLimiter(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent debe ser al menos 1: " + maxConcurrent);
        }
        this.semaphore = new Semaphore(maxConcurrent);
    }

    @Override
    public Permit acquire() {
        if (!semaphore.tryAcquire()) {
            throw new ServiceBusyException("No hay cupo para otra generación simultánea", RETRY_AFTER);
        }
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        };
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }
}
