package com.tunai.api.domain.exception;

/**
 * Los bytes no son una imagen reconocible, no se pueden leer o no cumplen el lado mínimo (RN-04). Se traduce a
 * {@code 400 INVALID_IMAGE}.
 */
public class InvalidImageException extends DomainException {

    public InvalidImageException(String message) {
        super(message);
    }

    public InvalidImageException(String message, Throwable cause) {
        super(message, null, cause);
    }
}
