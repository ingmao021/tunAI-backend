package com.tunai.api.application.port.out;

import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.VehicleImage;

/** Genera un modelo 3D a partir de una foto. Implementación: Tripo API v3 (§13.9). */
public interface ModelGeneratorPort {

    GeneratedModel generate(VehicleImage image);
}
