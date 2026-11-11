package com.tunai.api.domain.model;

import java.time.Instant;
import java.util.Objects;

/** Sesión que recibe el cliente tras iniciar sesión: un token opaco y cuándo deja de servir. */
public record AuthSession(String username, String token, Instant expiresAt) {

    public AuthSession {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
