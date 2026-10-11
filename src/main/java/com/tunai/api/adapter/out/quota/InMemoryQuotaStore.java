package com.tunai.api.adapter.out.quota;

import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.InsufficientCreditsException;
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
 * contadores (créditos de la instalación, IP y global) y el tráfico es mínimo (concurrencia ≤ 2), así que un lock
 * global es más simple y seguro que operaciones atómicas por clave.
 *
 * <p>Los créditos de cada instalación no se reinician. Los contadores de IP y global sí: al cambiar el día (00:00 en
 * la zona configurada) se descartan, y una reserva del día anterior que se confirme o libere después solo afecta a los
 * créditos de su instalación. Un reinicio del proceso vacía todo (limitación aceptada, RN-14): el cliente recuerda el
 * último saldo y no muestra más créditos de los que ya conocía.
 */
public final class InMemoryQuotaStore implements QuotaStorePort {

    private static final String GLOBAL_KEY = "global";

    private final QuotaLimits limits;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<InstallationId, UsageCounter> installations = new HashMap<>();
    private final Map<String, UsageCounter> daily = new HashMap<>();
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
            if (credits(installationId) < limits.creditsPerModel()) {
                throw new InsufficientCreditsException(
                        "Créditos insuficientes: cada modelo usa " + limits.creditsPerModel(),
                        statusOf(installationId, clientIp, now));
            }
            Duration untilReset = Duration.between(now, limits.nextReset(now));
            if (dailyUsed(ipKey(clientIp)) >= limits.perIp()) {
                throw new QuotaExceededException(
                        "Se alcanzó el máximo de generaciones por día desde esta red",
                        statusOf(installationId, clientIp, now),
                        untilReset);
            }
            if (dailyUsed(GLOBAL_KEY) >= limits.globalDaily()) {
                throw new GlobalLimitReachedException("Límite global diario alcanzado", untilReset);
            }
            installation(installationId).reserve();
            dailyCounter(ipKey(clientIp)).reserve();
            dailyCounter(GLOBAL_KEY).reserve();
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
                installation(reservation.installationId()).commit();
                if (reservation.day().equals(currentDay)) {
                    dailyCounter(ipKey(reservation.clientIp())).commit();
                    dailyCounter(GLOBAL_KEY).commit();
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
                installation(reservation.installationId()).release();
                if (reservation.day().equals(currentDay)) {
                    dailyCounter(ipKey(reservation.clientIp())).release();
                    dailyCounter(GLOBAL_KEY).release();
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

    /** El más restrictivo entre créditos e IP; ante empate se informa el de la instalación (RF-BE-015). */
    private QuotaStatus statusOf(InstallationId installationId, ClientIp clientIp, Instant now) {
        int credits = credits(installationId);
        int installationRemaining = credits / limits.creditsPerModel();
        int ipRemaining = Math.max(0, limits.perIp() - dailyUsed(ipKey(clientIp)));
        Instant resetsAt = limits.nextReset(now);
        return ipRemaining < installationRemaining
                ? new QuotaStatus(limits.perIp(), ipRemaining, resetsAt, credits, limits.creditsPerModel())
                : new QuotaStatus(
                        limits.modelsPerInstallation(),
                        installationRemaining,
                        resetsAt,
                        credits,
                        limits.creditsPerModel());
    }

    /** Saldo de la instalación; las reservas en curso ya están descontadas. */
    private int credits(InstallationId installationId) {
        UsageCounter counter = installations.get(installationId);
        int used = counter == null ? 0 : counter.used();
        return Math.max(0, limits.initialCredits() - used * limits.creditsPerModel());
    }

    private int dailyUsed(String key) {
        UsageCounter counter = daily.get(key);
        return counter == null ? 0 : counter.used();
    }

    private void rollDayIfNeeded(Instant now) {
        LocalDate today = limits.dayOf(now);
        if (!today.equals(currentDay)) {
            daily.clear();
            currentDay = today;
        }
    }

    private UsageCounter installation(InstallationId installationId) {
        return installations.computeIfAbsent(installationId, k -> new UsageCounter());
    }

    private UsageCounter dailyCounter(String key) {
        return daily.computeIfAbsent(key, k -> new UsageCounter());
    }

    private static String ipKey(ClientIp clientIp) {
        return "ip:" + clientIp.value();
    }
}
