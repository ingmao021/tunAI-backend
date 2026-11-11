package com.tunai.api.application.port.in;

import com.tunai.api.domain.exception.InvalidTokenException;
import com.tunai.api.domain.model.AccountId;

/** Identifica la cuenta a partir del token de sesión de cada petición. */
public interface AuthenticateUseCase {

    /**
     * @throws InvalidTokenException si el token falta, no es válido o caducó
     */
    AccountId authenticate(String token);
}
