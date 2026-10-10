package com.tunai.api.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Estado de cuota que ve el cliente: el límite más restrictivo entre instalación e IP (RF-BE-015). El límite global no
 * se expone para no revelar el presupuesto (flujo A5).
 */
public record QuotaStatus(int limit, int remaining, Instant resetsAt) {

    public QuotaStatus {
        Objects.requireNonNull(resetsAt, "resetsAt");
        if (limit < 0 || remaining < 0 || remaining > limit) {
            throw new IllegalArgumentException("Estado de cuota inválido: " + remaining + " de " + limit);
        }
    }
}
