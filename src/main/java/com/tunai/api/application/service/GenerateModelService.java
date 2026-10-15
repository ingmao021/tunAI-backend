package com.tunai.api.application.service;

import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.out.ConcurrencyLimiterPort;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.QuotaReservation;
import com.tunai.api.domain.model.QuotaStatus;
import java.util.Objects;

/**
 * Orquesta la generación (§13.5): permiso de concurrencia → reserva de cuota → proveedor → confirmar. Ante cualquier
 * fallo la reserva se libera (RN-03) y nunca se reintenta la llamada al proveedor (RN-13). Si el proveedor pudo haber
 * cobrado ({@link DomainException#providerMayHaveCharged()}), al usuario se le devuelven sus créditos pero el uso
 * diario de IP y global se mantiene, para que esos límites sigan acotando el gasto real.
 */
public class GenerateModelService implements GenerateModelUseCase {

    private final ConcurrencyLimiterPort concurrency;
    private final QuotaStorePort quota;
    private final ModelGeneratorPort generator;
    private final long maxModelBytes;

    public GenerateModelService(
            ConcurrencyLimiterPort concurrency,
            QuotaStorePort quota,
            ModelGeneratorPort generator,
            long maxModelBytes) {
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency");
        this.quota = Objects.requireNonNull(quota, "quota");
        this.generator = Objects.requireNonNull(generator, "generator");
        if (maxModelBytes <= 0) {
            throw new IllegalArgumentException("maxModelBytes debe ser positivo: " + maxModelBytes);
        }
        this.maxModelBytes = maxModelBytes;
    }

    @Override
    public GenerationResult generate(GenerateCommand command) {
        Objects.requireNonNull(command, "command");
        try (var permit = concurrency.acquire()) {
            QuotaReservation reservation = quota.tryReserve(command.installationId(), command.clientIp());
            boolean settled = false;
            try {
                GeneratedModel model = generator.generate(command.image());
                checkSize(model);
                QuotaStatus status = quota.commit(reservation);
                settled = true;
                return new GenerationResult(model, status);
            } catch (DomainException e) {
                if (e.providerMayHaveCharged()) {
                    quota.releaseKeepingDailyUsage(reservation);
                    settled = true;
                }
                throw e;
            } finally {
                if (!settled) {
                    quota.release(reservation);
                }
            }
        }
    }

    /** Un modelo que llegó hasta aquí ya lo cobró el proveedor, aunque se descarte. */
    private void checkSize(GeneratedModel model) {
        if (model.sizeInBytes() > maxModelBytes) {
            throw new ProviderFailureException(
                    "El modelo generado pesa " + model.sizeInBytes() + " bytes; el máximo es " + maxModelBytes
                            + " (RF-BE-013)",
                    null,
                    true);
        }
    }
}
