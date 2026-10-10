package com.tunai.api.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Límites diarios de generaciones exitosas (RN-02, RN-10, RN-11) y la zona horaria en la que el día se reinicia a las
 * 00:00 (RF-BE-017).
 */
public record QuotaLimits(int perInstallation, int perIp, int globalDaily, ZoneId zone) {

    public QuotaLimits {
        Objects.requireNonNull(zone, "zone");
        if (perInstallation < 1 || perIp < 1 || globalDaily < 1) {
            throw new IllegalArgumentException("Los límites deben ser al menos 1");
        }
    }

    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** Próximo reinicio: las 00:00 del día siguiente en la zona configurada. */
    public Instant nextReset(Instant now) {
        return dayOf(now).plusDays(1).atStartOfDay(zone).toInstant();
    }
}
