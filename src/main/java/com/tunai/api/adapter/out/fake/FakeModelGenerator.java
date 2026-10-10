package com.tunai.api.adapter.out.fake;

import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.VehicleImage;
import java.time.Duration;
import java.util.Objects;

/**
 * Generador de desarrollo (RF-BE-021): devuelve siempre el mismo GLB de ejemplo sin llamar a ningún proveedor, de modo
 * que el desarrollo no gasta créditos (RN-15). Simula la latencia del proveedor con un retardo configurable.
 */
public final class FakeModelGenerator implements ModelGeneratorPort {

    private final GeneratedModel sample;
    private final Duration delay;

    /**
     * @param sampleGlb GLB que se devuelve en cada generación; se valida al construir para fallar al arrancar
     * @param delay retardo simulado por generación
     */
    public FakeModelGenerator(byte[] sampleGlb, Duration delay) {
        this.sample = GeneratedModel.fromGlb(sampleGlb);
        this.delay = Objects.requireNonNull(delay, "delay");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay no puede ser negativo: " + delay);
        }
    }

    @Override
    public GeneratedModel generate(VehicleImage image, GenerationOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        simulateLatency();
        return sample;
    }

    private void simulateLatency() {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderFailureException("Generación fake interrumpida", e);
        }
    }
}
