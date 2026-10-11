package com.tunai.api.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.MutableClock;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.InsufficientCreditsException;
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

    /** Costo de cada modelo en los créditos de la instalación. */
    protected static final int COST = 15;

    /** 2026-10-09 10:00 en Bogotá (UTC-5). */
    protected static final Instant MORNING = Instant.parse("2026-10-09T15:00:00Z");

    /** 00:00 del 10 de octubre en Bogotá. */
    protected static final Instant NEXT_MIDNIGHT = Instant.parse("2026-10-10T05:00:00Z");

    protected final MutableClock clock = new MutableClock(MORNING);

    protected abstract QuotaStorePort createStore(QuotaLimits limits, MutableClock clock);

    /** Almacén cuyas instalaciones alcanzan para {@code models} modelos. */
    private QuotaStorePort store(int models, int perIp, int global) {
        return createStore(new QuotaLimits(models * COST, COST, perIp, global, BOGOTA), clock);
    }

    private static QuotaStatus status(int limit, int remaining, int credits) {
        return new QuotaStatus(limit, remaining, NEXT_MIDNIGHT, credits, COST);
    }

    private static InstallationId newInstallation() {
        return new InstallationId(UUID.randomUUID());
    }

    private static ClientIp ip(String value) {
        return ClientIp.parse(value).orElseThrow();
    }

    @Test
    void aFreshInstallationHasAllItsCredits() {
        QuotaStatus status = store(2, 4, 6).status(newInstallation(), ip("1.1.1.1"));

        assertThat(status).isEqualTo(status(2, 2, 30));
    }

    @Test
    void committedGenerationsSpendCredits() {
        QuotaStorePort store = store(2, 4, 6);
        InstallationId installation = newInstallation();

        QuotaStatus afterCommit = store.commit(store.tryReserve(installation, ip("1.1.1.1")));

        assertThat(afterCommit).isEqualTo(status(2, 1, 15));
        assertThat(store.status(installation, ip("1.1.1.1"))).isEqualTo(status(2, 1, 15));
    }

    @Test
    void withoutCreditsForAnotherModelTheReservationIsRejectedWithoutRetryAfter() {
        QuotaStorePort store = store(2, 4, 6);
        InstallationId installation = newInstallation();
        store.commit(store.tryReserve(installation, ip("1.1.1.1")));
        store.commit(store.tryReserve(installation, ip("1.1.1.1")));

        assertThatThrownBy(() -> store.tryReserve(installation, ip("1.1.1.1")))
                .isInstanceOfSatisfying(InsufficientCreditsException.class, e -> {
                    assertThat(e.status()).isEqualTo(status(2, 0, 0));
                    assertThat(e.retryAfter()).isEmpty();
                });
    }

    @Test
    void leftoverCreditsBelowTheCostAreReportedButCannotBeSpent() {
        QuotaStorePort store = createStore(new QuotaLimits(40, COST, 4, 6, BOGOTA), clock);
        InstallationId installation = newInstallation();
        store.commit(store.tryReserve(installation, ip("1.1.1.2")));
        store.commit(store.tryReserve(installation, ip("1.1.1.2")));

        assertThat(store.status(installation, ip("1.1.1.2"))).isEqualTo(status(2, 0, 10));
        assertThatThrownBy(() -> store.tryReserve(installation, ip("1.1.1.2")))
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    void theIpLimitAppliesAcrossInstallations() {
        QuotaStorePort store = store(2, 2, 6);
        store.commit(store.tryReserve(newInstallation(), ip("2.2.2.2")));
        store.commit(store.tryReserve(newInstallation(), ip("2.2.2.2")));

        InstallationId invented = newInstallation();
        assertThatThrownBy(() -> store.tryReserve(invented, ip("2.2.2.2")))
                .isInstanceOfSatisfying(QuotaExceededException.class, e -> {
                    assertThat(e.status()).isEqualTo(status(2, 0, 30));
                    assertThat(e.retryAfter()).contains(Duration.between(MORNING, NEXT_MIDNIGHT));
                });
        assertThat(store.status(invented, ip("3.3.3.3")).remaining()).isEqualTo(2);
    }

    @Test
    void theReportedStatusIsTheMostRestrictiveBetweenCreditsAndIp() {
        QuotaStorePort store = store(3, 3, 6);
        store.commit(store.tryReserve(newInstallation(), ip("4.4.4.4")));
        store.commit(store.tryReserve(newInstallation(), ip("4.4.4.4")));

        assertThat(store.status(newInstallation(), ip("4.4.4.4"))).isEqualTo(status(3, 1, 45));
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
        assertThat(store.status(installation, ip("5.5.5.3"))).isEqualTo(status(5, 5, 75));
    }

    @Test
    void releasingAReservationGivesTheCreditsBack() {
        QuotaStorePort store = store(1, 1, 1);
        InstallationId installation = newInstallation();

        store.release(store.tryReserve(installation, ip("6.6.6.6")));

        assertThat(store.status(installation, ip("6.6.6.6"))).isEqualTo(status(1, 1, 15));
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

        assertThat(store.status(installation, ip("7.7.7.7")).credits()).isEqualTo(15);
    }

    @Test
    void reservationsInProgressCountAsSpent() {
        QuotaStorePort store = store(2, 4, 4);
        InstallationId installation = newInstallation();
        store.tryReserve(installation, ip("8.8.8.8"));
        store.tryReserve(installation, ip("8.8.8.8"));

        assertThat(store.status(installation, ip("8.8.8.8")).credits()).isZero();
        assertThatThrownBy(() -> store.tryReserve(installation, ip("8.8.8.8")))
                .isInstanceOf(InsufficientCreditsException.class);
    }

    @Test
    void creditsDoNotResetAtMidnightButTheIpLimitDoes() {
        QuotaStorePort store = store(1, 1, 1);
        InstallationId installation = newInstallation();
        store.commit(store.tryReserve(installation, ip("9.9.9.9")));
        assertThatThrownBy(() -> store.tryReserve(newInstallation(), ip("9.9.9.9")))
                .isInstanceOf(QuotaExceededException.class);

        clock.set(NEXT_MIDNIGHT);

        QuotaStatus status = store.status(installation, ip("9.9.9.9"));
        assertThat(status.credits()).isZero();
        assertThat(status.resetsAt()).isEqualTo(NEXT_MIDNIGHT.plus(Duration.ofDays(1)));
        assertThatThrownBy(() -> store.tryReserve(installation, ip("9.9.9.9")))
                .isInstanceOf(InsufficientCreditsException.class);
        store.commit(store.tryReserve(newInstallation(), ip("9.9.9.9")));
    }

    @Test
    void aReservationFromYesterdaySpendsCreditsButNotTodaysIpLimit() {
        QuotaStorePort store = store(2, 1, 1);
        InstallationId installation = newInstallation();
        QuotaReservation yesterday = store.tryReserve(installation, ip("10.0.0.1"));

        clock.set(NEXT_MIDNIGHT.plusSeconds(10));
        store.commit(yesterday);

        QuotaStatus status = store.status(installation, ip("10.0.0.1"));
        assertThat(status.credits()).isEqualTo(15);
        assertThat(status.remaining()).isEqualTo(1);
        store.commit(store.tryReserve(installation, ip("10.0.0.1")));
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
