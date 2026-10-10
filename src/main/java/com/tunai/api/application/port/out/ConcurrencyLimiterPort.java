package com.tunai.api.application.port.out;

/** Limita las llamadas simultáneas al proveedor (RF-BE-011). */
public interface ConcurrencyLimiterPort {

    /**
     * Obtiene un permiso sin esperar.
     *
     * @throws com.tunai.api.domain.exception.ServiceBusyException si no hay cupo
     */
    Permit acquire();

    /** Se libera al cerrarse; cerrarlo más de una vez no tiene efecto. */
    interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
