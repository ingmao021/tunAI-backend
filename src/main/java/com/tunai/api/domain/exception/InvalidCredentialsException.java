package com.tunai.api.domain.exception;

/** Usuario o contraseña incorrectos. Se traduce a {@code 401 INVALID_CREDENTIALS}; no revela cuál de los dos falló. */
public class InvalidCredentialsException extends DomainException {

    public InvalidCredentialsException() {
        super("Usuario o contraseña incorrectos.");
    }
}
