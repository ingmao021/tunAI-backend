package com.tunai.api.application.port.out;

import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaReservation;
import com.tunai.api.domain.model.QuotaStatus;

/**
 * Almacén de cuota con el patrón reserva → confirmar / liberar (§13.3). La implementación en memoria es la del MVP; una
 * persistente debe cumplir el mismo contrato (§13.6).
 */
public interface QuotaStorePort {

    /**
     * Reserva un cupo de forma atómica en los contadores de instalación, IP y global.
     *
     * @throws com.tunai.api.domain.exception.InsufficientCreditsException si la instalación no tiene créditos para otro
     *     modelo
     * @throws com.tunai.api.domain.exception.QuotaExceededException si se agotó el límite diario de la IP
     * @throws com.tunai.api.domain.exception.GlobalLimitReachedException si se agotó el límite global diario
     */
    QuotaReservation tryReserve(InstallationId installationId, ClientIp clientIp);

    /** Convierte la reserva en una generación exitosa y devuelve el estado resultante. */
    QuotaStatus commit(QuotaReservation reservation);

    /** Devuelve el cupo reservado. Es idempotente: liberar dos veces o tras confirmar no tiene efecto. */
    void release(QuotaReservation reservation);

    /**
     * La generación falló pero el proveedor pudo haberla cobrado: devuelve los créditos de la instalación (el usuario
     * no recibió modelo) y mantiene el uso diario de la IP y global, que protege el saldo del proveedor. Es
     * idempotente, como {@link #release}.
     */
    void releaseKeepingDailyUsage(QuotaReservation reservation);

    /** Estado actual sin consumir cuota (RF-BE-002). */
    QuotaStatus status(InstallationId installationId, ClientIp clientIp);
}
