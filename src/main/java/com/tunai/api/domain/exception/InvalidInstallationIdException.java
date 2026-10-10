package com.tunai.api.domain.exception;

/** Header {@code X-Installation-Id} ausente o sin formato UUID. Se traduce a {@code 400 INVALID_INSTALLATION_ID}. */
public class InvalidInstallationIdException extends DomainException {

    public InvalidInstallationIdException(String message) {
        super(message);
    }
}
