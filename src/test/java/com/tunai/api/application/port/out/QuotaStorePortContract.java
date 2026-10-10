package com.tunai.api.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.MutableClock;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.QuotaExceededException;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaLimits;
import com.tunai.api.domain.model.QuotaReservation;
import com.tunai.api.domain.model.QuotaStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Contrato de {@link QuotaStorePort} (Anexo C, §13.4 principio L): cualquier implementación, en memoria o persistente,
 * debe pasar esta suite.
 */
public abstract class QuotaStorePortContract {

    protected static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    /** 2026-10-09 10:00 en Bogotá (UTC-5). */
    protected static final Instant MORNING = Instant.parse("2026-10-09T15:00:00Z");

    /** 00:00 del 10 de octubre en Bogotá. */
    protected static final Instant NEXT_MIDNIGHT = Instant.parse("2026-10-10T05:00:00Z");

    protected final MutableClock clock = new MutableClock(MORNING);

    protected abstract QuotaStorePort createStore(QuotaLimits limits, MutableClock clock);

    private QuotaStorePort store(int perInstallation, int perIp, int global) {
        return createStore(new QuotaLimits(perInstallation, perIp, global, BOGOTA), clock);
    }

    private static InstallationId newInstallation() {
        return new InstallationId(UUID.randomUUID());
    }

    private static ClientIp ip(String value) {
        return ClientIp.parse(value).orElseThrow();
    }

    @Test
    void aFreshInstallationHasTheFullQuotaAndResetsAtMidnightInTheConfiguredZone() {
        QuotaStatus status = store(2, 4, 6).status(newInstallation(), ip("1.1.1.1"));

        assertThat(status).isEqualTo(new QuotaStatus(2, 2, NEXT_MIDNIGHT));
    }

    @Test
    void committedGenerationsReduceTheRemainingQuota() {
        QuotaStorePort store = store(2, 4, 6);
        InstallationId installation = newInstallation();

        QuotaStatus afterCommit = store.commit(store.tryReserve(installation, ip("1.1.1.1")));

        assertThat(afterCommit.remaining()).isEqualTo(1);
        assertThat(store.status(installation, ip("1.1.1.1")).remaining()).isEqualTo(1);
    }

    @Test
    void theThirdGenerationOfTheDayIsRejectedWithRetryAfterUntilMidnight() {
        QuotaStorePort store = store(2, 4, 6);
        InstallationId installation = newInstallation();
        store.commit(store.tryReserve(installation, ip("1.1.1.1")));
        store.commit(store.tryReserve(installation, ip("1.1.1.1")));

        assertThatThrownBy(() -> store.tryReserve(installation, ip("1.1.1.1")))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.status()).isEqualTo(new QuotaStatus(2, 0, NEXT_MIDNIGHT));
                    assertThat(e.retryAfter()).contains(Duration.between(MORNING, NEXT_MIDNIGHT));
                });
    }

    @Test
    void theIpLimitAppliesAcrossInstallations() {
        QuotaStorePort store = store(2, 2, 6);
        store.commit(store.tryReserve(newInstallation(), ip("2.2.2.2")));
        store.commit(store.tryReserve(newInstallation(), ip("2.2.2.2")));

        InstallationId invented = newInstallation();
        assertThatThrownBy(() -> store.tryReserve(invented, ip("2.2.2.2")))
                .isInstanceOfSatisfying(
                        QuotaExceededException.class,
                        e -> assertThat(e.status().remaining()).isZero());
        assertThat(store.status(invented, ip("3.3.3.3")).remaining()).isEqualTo(2);
    }

    @Test
    void theReportedStatusIsTheMostRestrictiveBetweenInstallationAndIp() {
        QuotaStorePort store = store(2, 3, 6);
        store.commit(store.tryReserve(newInstallation(), ip("4.4.4.4")));
        store.commit(store.tryReserve(newInstallation(), ip("4.4.4.4")));

        assertThat(store.status(newInstallation(), ip("4.4.4.4"))).isEqualTo(new QuotaStatus(3, 1, NEXT_MIDNIGHT));
    }

    @Test
    void theGlobalLimitIsReportedSeparatelyAndNotExposedInTheStatus() {
        QuotaStorePort store = store(5, 5, 2);
        store.commit(store.tryReserve(newInstallation(), ip("5.5.5.1")));
        store.commit(store.tryReserve(newInstallation(), ip("5.5.5.2")));

        InstallationId installation = newInstallation();
        assertThatThrownBy(() -> store.tryReserve(installation, ip("5.5.5.3")))
                .isInstanceOfSatisfying(GlobalLimitReachedException.class, e -> assertThat(e.retryAfter())
                        .contains(Duration.between(MORNING, NEXT_MIDNIGHT)));
        assertThat(store.status(installation, ip("5.5.5.3"))).isEqualTo(new QuotaStatus(5, 5, NEXT_MIDNIGHT));
    }

    @Test
    void releasingAReservationGivesTheQuotaBack() {
        QuotaStorePort store = store(1, 1, 1);
        InstallationId installation = newInstallation();

        store.release(store.tryReserve(installation, ip("6.6.6.6")));

        assertThat(store.status(installation, ip("6.6.6.6")).remaining()).isEqualTo(1);
        store.commit(store.tryReserve(installation, ip("6.6.6.6")));
    }

    @Test
    void releaseIsIdempotentAndHasNoEffectAfterCommit() {
        QuotaStorePort store = store(2, 2, 2);
        InstallationId installation = newInstallation();
        QuotaReservation committed = store.tryReserve(installation, ip("7.7.7.7"));
        store.commit(committed);
        QuotaReservation released = store.tryReserve(installation, ip("7.7.7.7"));
        store.release(released);

        store.release(released);
        store.release(committed);

        assertThat(store.status(installation, ip("7.7.7.7")).remaining()).isEqualTo(1);
    }

    @Test
    void reservationsInProgressCountAsUsed() {
        QuotaStorePort store = store(2, 2, 2);
        InstallationId installation = newInstallation();
        store.tryReserve(installation, ip("8.8.8.8"));
        store.tryReserve(installation, ip("8.8.8.8"));

        assertThat(store.status(installation, ip("8.8.8.8")).remaining()).isZero();
        assertThatThrownBy(() -> store.tryReserve(installation, ip("8.8.8.8")))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void theQuotaResetsAtMidnightInTheConfiguredZone() {
        QuotaStorePort store = store(1, 1, 1);
        InstallationId installation = newInstallation();
        store.commit(store.tryReserve(installation, ip("9.9.9.9")));

        clock.set(NEXT_MIDNIGHT.minusSeconds(1));
        assertThatThrownBy(() -> store.tryReserve(installation, ip("9.9.9.9")))
                .isInstanceOf(QuotaExceededException.class);

        clock.set(NEXT_MIDNIGHT);
        QuotaStatus status = store.status(installation, ip("9.9.9.9"));
        assertThat(status.remaining()).isEqualTo(1);
        assertThat(status.resetsAt()).isEqualTo(NEXT_MIDNIGHT.plus(Duration.ofDays(1)));
    }

    @Test
    void aReservationFromYesterdayDoesNotCountToday() {
        QuotaStorePort store = store(1, 1, 1);
        InstallationId installation = newInstallation();
        QuotaReservation yesterday = store.tryReserve(installation, ip("10.0.0.1"));

        clock.set(NEXT_MIDNIGHT.plusSeconds(10));
        store.commit(yesterday);

        assertThat(store.status(installation, ip("10.0.0.1")).remaining()).isEqualTo(1);
    }

    @Test
    void concurrentReservationsNeverExceedTheLimit() throws Exception {
        QuotaStorePort store = store(100, 100, 5);
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String address = "11.0.0." + i;
            attempts.add(() -> {
                start.await();
                try {
                    store.tryReserve(newInstallation(), ip(address));
                    return true;
                } catch (GlobalLimitReachedException e) {
                    return false;
                }
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> attempt : attempts) {
                results.add(executor.submit(attempt));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }
}
