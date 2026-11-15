package com.tunai.api.application.port.out;

import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.VehicleImage;

/** Puerto de salida hacia el proveedor de generación 3D. La imagen ya llega validada por el dominio. */
public interface ModelGeneratorPort {

    /** Genera el modelo de esta foto. Si ya hay una generación empezada para ella, se une en vez de crear otra. */
    GeneratedModel generate(VehicleImage image);

    /**
     * Indica si ya hay una generación empezada (o terminada y sin entregar) para esta foto, de un intento anterior que
     * agotó el tiempo. En ese caso {@link #resume} la retoma en vez de crear otra, así que no cuesta otro modelo.
     */
    default boolean hasPendingGeneration(VehicleImage image) {
        return false;
    }

    /**
     * Retoma la generación ya empezada para esta foto y espera su modelo. <b>Nunca</b> crea una nueva: una retoma no la
     * confirmó el usuario, así que no puede gastar otro modelo (RN-13).
     *
     * @throws ProviderFailureException si ya no hay ninguna generación que retomar
     */
    default GeneratedModel resume(VehicleImage image) {
        throw new ProviderFailureException("No hay una generación en curso que retomar", null, true);
    }
}
