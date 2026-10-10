package com.tunai.api.application.port.out;

import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.VehicleImage;

/**
 * Genera un modelo 3D a partir de una foto. Implementaciones: {@code fake} (desarrollo, RF-BE-021) y
 * {@code stability} (Fase 4).
 */
public interface ModelGeneratorPort {

    GeneratedModel generate(VehicleImage image, GenerationOptions options);
}
