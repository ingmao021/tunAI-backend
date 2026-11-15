package com.tunai.api.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Cupo reservado antes de llamar al proveedor; se confirma si hay éxito o se libera si falla (§13.3, RN-03).
 *
 * @param countsDaily {@code false} cuando la reserva retoma una generación que el proveedor ya empezó y cuyo uso diario
 *     de IP y global quedó contado en el intento anterior: así reintentar no cuenta dos veces
 */
public record QuotaReservation(UUID id, AccountId accountId, ClientIp clientIp, LocalDate day, boolean countsDaily) {

    public QuotaReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(clientIp, "clientIp");
        Objects.requireNonNull(day, "day");
    }

    public QuotaReservation(UUID id, AccountId accountId, ClientIp clientIp, LocalDate day) {
        this(id, accountId, clientIp, day, true);
    }
}
