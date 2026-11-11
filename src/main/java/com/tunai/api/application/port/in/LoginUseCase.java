package com.tunai.api.application.port.in;

import com.tunai.api.domain.exception.InvalidCredentialsException;
import com.tunai.api.domain.model.AuthSession;

/** Inicio de sesión de TunAI Desktop. */
public interface LoginUseCase {

    /**
     * @throws InvalidCredentialsException si el usuario no existe o la contraseña no coincide
     */
    AuthSession login(String username, String password);
}
