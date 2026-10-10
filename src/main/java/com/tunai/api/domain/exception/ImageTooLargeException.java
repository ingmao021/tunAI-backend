package com.tunai.api.domain.exception;

/** La imagen supera el tamaño máximo (RN-04). Se traduce a {@code 413 IMAGE_TOO_LARGE}. */
public class ImageTooLargeException extends DomainException {

    public ImageTooLargeException(String message) {
        super(message);
    }
}
