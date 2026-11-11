package com.tunai.api.domain.exception;

/** Falta el token de sesión, es inválido o caducó. Se traduce a {@code 401 UNAUTHORIZED}. */
public class InvalidTokenException extends DomainException {

    public InvalidTokenException() {
        super("Tu sesión no es válida o caducó. Inicia sesión de nuevo.");
    }
}
