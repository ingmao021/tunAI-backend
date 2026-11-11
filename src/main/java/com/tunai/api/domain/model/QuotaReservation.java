package com.tunai.api.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Cupo reservado antes de llamar al proveedor; se confirma si hay éxito o se libera si falla (§13.3, RN-03). */
public record QuotaReservation(UUID id, AccountId accountId, ClientIp clientIp, LocalDate day) {

    public QuotaReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(clientIp, "clientIp");
        Objects.requireNonNull(day, "day");
    }
}
