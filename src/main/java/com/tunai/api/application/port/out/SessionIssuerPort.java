package com.tunai.api.application.port.out;

import com.tunai.api.domain.model.AuthSession;

/** Emite la sesión de un usuario ya autenticado. */
public interface SessionIssuerPort {

    AuthSession issue(String username);
}
