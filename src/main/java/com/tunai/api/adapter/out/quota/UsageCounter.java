package com.tunai.api.adapter.out.quota;

/**
 * Generaciones confirmadas y reservadas (en curso) de una clave: los modelos de una instalación o los del día de una IP
 * o del total. No es thread-safe: lo protege el lock de {@link InMemoryQuotaStore}.
 */
final class UsageCounter {

    private int committed;
    private int reserved;

    /** Las reservas en curso cuentan como usadas, para que dos peticiones simultáneas no superen el límite. */
    int used() {
        return committed + reserved;
    }

    void reserve() {
        reserved++;
    }

    void commit() {
        reserved--;
        committed++;
    }

    void release() {
        reserved--;
    }
}
