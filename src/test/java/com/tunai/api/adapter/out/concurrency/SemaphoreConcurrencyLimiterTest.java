package com.tunai.api.adapter.out.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.application.port.out.ConcurrencyLimiterPort.Permit;
import com.tunai.api.domain.exception.ServiceBusyException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SemaphoreConcurrencyLimiterTest {

    @Test
    void rejectsWithoutWaitingWhenAllPermitsAreTaken() {
        SemaphoreConcurrencyLimiter limiter = new SemaphoreConcurrencyLimiter(2);
        limiter.acquire();
        limiter.acquire();

        assertThatThrownBy(limiter::acquire)
                .isInstanceOfSatisfying(ServiceBusyException.class, e -> assertThat(e.retryAfter())
                        .contains(Duration.ofSeconds(5)));
    }

    @Test
    void closingAPermitFreesTheSlotOnlyOnce() {
        SemaphoreConcurrencyLimiter limiter = new SemaphoreConcurrencyLimiter(1);
        Permit permit = limiter.acquire();

        permit.close();
        permit.close();

        assertThat(limiter.availablePermits()).isEqualTo(1);
        try (Permit again = limiter.acquire()) {
            assertThat(limiter.availablePermits()).isZero();
        }
    }

    @Test
    void rejectsANonPositiveLimit() {
        assertThatThrownBy(() -> new SemaphoreConcurrencyLimiter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
