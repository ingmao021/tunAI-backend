package com.tunai.api.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Límites de uso (RN-02, RN-10, RN-11). Cada instalación recibe {@code initialCredits} una sola vez y cada modelo
 * generado con éxito descuenta {@code creditsPerModel}; los créditos no se renuevan. Los límites por IP y global son
 * diarios y se reinician a las 00:00 en {@code zone} (RF-BE-017).
 */
public record QuotaLimits(int initialCredits, int creditsPerModel, int perIp, int globalDaily, ZoneId zone) {

    public QuotaLimits {
        Objects.requireNonNull(zone, "zone");
        if (creditsPerModel < 1 || perIp < 1 || globalDaily < 1) {
            throw new IllegalArgumentException("Los límites deben ser al menos 1");
        }
        if (initialCredits < 0) {
            throw new IllegalArgumentException("Los créditos iniciales no pueden ser negativos: " + initialCredits);
        }
    }

    /** Modelos que alcanza a generar una instalación nueva. */
    public int modelsPerInstallation() {
        return initialCredits / creditsPerModel;
    }

    public LocalDate dayOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** Próximo reinicio de los límites diarios: las 00:00 del día siguiente en la zona configurada. */
    public Instant nextReset(Instant now) {
        return dayOf(now).plusDays(1).atStartOfDay(zone).toInstant();
    }
}
