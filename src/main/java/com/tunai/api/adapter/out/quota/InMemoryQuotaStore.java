package com.tunai.api.adapter.out.quota;

import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.QuotaExceededException;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaLimits;
import com.tunai.api.domain.model.QuotaReservation;
import com.tunai.api.domain.model.QuotaStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Cuota en memoria (§13.6). Todas las operaciones toman un único lock: la reserva debe ser atómica sobre tres
 * contadores (instalación, IP y global) y el tráfico es mínimo (concurrencia ≤ 2), así que un lock global es más simple
 * y seguro que operaciones atómicas por clave.
 *
 * <p>Al cambiar el día (00:00 en la zona configurada) se descartan todos los contadores y reservas pendientes; una
 * reserva del día anterior que se confirme o libere después no afecta el día nuevo. Un reinicio del proceso vacía la
 * cuota (limitación aceptada, RN-14).
 */
public final class InMemoryQuotaStore implements QuotaStorePort {

    private static final String GLOBAL_KEY = "global";

    private final QuotaLimits limits;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, DailyCounter> counters = new HashMap<>();
    private final Map<UUID, QuotaReservation> pending = new HashMap<>();
    private LocalDate currentDay;

    public InMemoryQuotaStore(QuotaLimits limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public QuotaReservation tryReserve(InstallationId installationId, ClientIp clientIp) {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(clientIp, "clientIp");
        synchronized (lock) {
            Instant now = clock.instant();
            rollDayIfNeeded(now);
            Duration untilReset = Duration.between(now, limits.nextReset(now));
            if (counter(installationKey(installationId)).used() >= limits.perInstallation()) {
                throw new QuotaExceededException(
                        "Se alcanzó el máximo de " + limits.perInstallation() + " generaciones por día",
                        statusOf(installationId, clientIp, now),
                        untilReset);
            }
            if (counter(ipKey(clientIp)).used() >= limits.perIp()) {
                throw new QuotaExceededException(
                        "Se alcanzó el máximo de generaciones por día desde esta red",
                        statusOf(installationId, clientIp, now),
                        untilReset);
            }
            if (counter(GLOBAL_KEY).used() >= limits.globalDaily()) {
                throw new GlobalLimitReachedException("Límite global diario alcanzado", untilReset);
            }
            for (String key : keys(installationId, clientIp)) {
                counter(key).reserve();
            }
            QuotaReservation reservation =
                    new QuotaReservation(UUID.randomUUID(), installationId, clientIp, currentDay);
            pending.put(reservation.id(), reservation);
            return reservation;
        }
    }

    @Override
    public QuotaStatus commit(QuotaReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        synchronized (lock) {
            Instant now = clock.instant();
            rollDayIfNeeded(now);
            if (pending.remove(reservation.id()) != null) {
                for (String key : keys(reservation.installationId(), reservation.clientIp())) {
                    counter(key).commit();
                }
            }
            return statusOf(reservation.installationId(), reservation.clientIp(), now);
        }
    }

    @Override
    public void release(QuotaReservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        synchronized (lock) {
            rollDayIfNeeded(clock.instant());
            if (pending.remove(reservation.id()) != null) {
                for (String key : keys(reservation.installationId(), reservation.clientIp())) {
                    counter(key).release();
                }
            }
        }
    }

    @Override
    public QuotaStatus status(InstallationId installationId, ClientIp clientIp) {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(clientIp, "clientIp");
        synchronized (lock) {
            Instant now = clock.instant();
            rollDayIfNeeded(now);
            return statusOf(installationId, clientIp, now);
        }
    }

    /** El más restrictivo entre instalación e IP; ante empate se informa el de la instalación (RF-BE-015). */
    private QuotaStatus statusOf(InstallationId installationId, ClientIp clientIp, Instant now) {
        int installationRemaining = remaining(limits.perInstallation(), installationKey(installationId));
        int ipRemaining = remaining(limits.perIp(), ipKey(clientIp));
        Instant resetsAt = limits.nextReset(now);
        return ipRemaining < installationRemaining
                ? new QuotaStatus(limits.perIp(), ipRemaining, resetsAt)
                : new QuotaStatus(limits.perInstallation(), installationRemaining, resetsAt);
    }

    private int remaining(int limit, String key) {
        DailyCounter counter = counters.get(key);
        int used = counter == null ? 0 : counter.used();
        return Math.max(0, limit - used);
    }

    private void rollDayIfNeeded(Instant now) {
        LocalDate today = limits.dayOf(now);
        if (!today.equals(currentDay)) {
            counters.clear();
            pending.clear();
            currentDay = today;
        }
    }

    private DailyCounter counter(String key) {
        return counters.computeIfAbsent(key, k -> new DailyCounter());
    }

    private static String[] keys(InstallationId installationId, ClientIp clientIp) {
        return new String[] {installationKey(installationId), ipKey(clientIp), GLOBAL_KEY};
    }

    private static String installationKey(InstallationId installationId) {
        return "installation:" + installationId.value();
    }

    private static String ipKey(ClientIp clientIp) {
        return "ip:" + clientIp.value();
    }
}
