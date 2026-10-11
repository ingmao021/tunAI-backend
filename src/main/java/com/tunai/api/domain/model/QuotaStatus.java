package com.tunai.api.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Estado de cuota que ve el cliente (RF-BE-015). {@code limit} y {@code remaining} cuentan modelos: el límite más
 * restrictivo entre los créditos de la instalación y el límite diario de la IP. {@code credits} es el saldo de la
 * instalación y {@code creditsPerModel} lo que cuesta cada modelo. El límite global no se expone para no revelar el
 * presupuesto (flujo A5).
 */
public record QuotaStatus(int limit, int remaining, Instant resetsAt, int credits, int creditsPerModel) {

    public QuotaStatus {
        Objects.requireNonNull(resetsAt, "resetsAt");
        if (limit < 0 || remaining < 0 || remaining > limit) {
            throw new IllegalArgumentException("Estado de cuota inválido: " + remaining + " de " + limit);
        }
        if (credits < 0 || creditsPerModel < 1) {
            throw new IllegalArgumentException(
                    "Créditos inválidos: " + credits + " a " + creditsPerModel + " por modelo");
        }
    }
}
