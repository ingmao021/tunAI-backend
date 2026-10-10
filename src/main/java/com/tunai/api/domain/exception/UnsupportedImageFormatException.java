package com.tunai.api.domain.exception;

/**
 * Imagen reconocible por su firma de bytes pero de un formato no soportado (WEBP, HEIC, GIF, BMP). Se traduce a
 * {@code 415 UNSUPPORTED_MEDIA_TYPE}.
 */
public class UnsupportedImageFormatException extends DomainException {

    public UnsupportedImageFormatException(String message) {
        super(message);
    }
}
