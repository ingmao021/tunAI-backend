package com.tunai.api.application.service;

import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import java.util.Objects;

/**
 * Orquesta la generación (§13.5). La reserva de cuota y el permiso de concurrencia se agregan en la Fase 3; nunca se
 * reintenta una llamada al proveedor (RN-13).
 */
public class GenerateModelService implements GenerateModelUseCase {

    private final ModelGeneratorPort generator;
    private final GenerationOptions options;
    private final long maxModelBytes;

    public GenerateModelService(ModelGeneratorPort generator, GenerationOptions options, long maxModelBytes) {
        this.generator = Objects.requireNonNull(generator, "generator");
        this.options = Objects.requireNonNull(options, "options");
        if (maxModelBytes <= 0) {
            throw new IllegalArgumentException("maxModelBytes debe ser positivo: " + maxModelBytes);
        }
        this.maxModelBytes = maxModelBytes;
    }

    @Override
    public GeneratedModel generate(GenerateCommand command) {
        Objects.requireNonNull(command, "command");
        GeneratedModel model = generator.generate(command.image(), options);
        if (model.sizeInBytes() > maxModelBytes) {
            throw new ProviderFailureException("El modelo generado pesa " + model.sizeInBytes()
                    + " bytes; el máximo es " + maxModelBytes + " (RF-BE-013)");
        }
        return model;
    }
}
