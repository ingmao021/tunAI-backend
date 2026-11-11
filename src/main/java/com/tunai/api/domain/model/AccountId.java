package com.tunai.api.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Cuenta que inició sesión: dueña de los créditos. Es el usuario en minúsculas, sin distinguir mayúsculas. */
public record AccountId(String value) {

    public AccountId {
        Objects.requireNonNull(value, "value");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("La cuenta no puede estar vacía");
        }
    }

    /** Huella corta para los logs: la cuenta no se registra en claro (RNF-BE-009). */
    public String hashForLogs() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
