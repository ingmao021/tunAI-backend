package com.tunai.api.domain.model;

import com.tunai.api.domain.exception.InvalidInstallationIdException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identificador de una instalación de TunAI Desktop (header {@code X-Installation-Id}). Es solo un identificador de
 * cuota, no una credencial (§5).
 */
public record InstallationId(UUID value) {

    /** Forma canónica de 36 caracteres; {@link UUID#fromString} acepta variantes como {@code 1-1-1-1-1}. */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public InstallationId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * @throws InvalidInstallationIdException si el valor es nulo, está vacío o no es un UUID canónico (RF-BE-004)
     */
    public static InstallationId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidInstallationIdException("Falta el header X-Installation-Id");
        }
        String trimmed = raw.trim();
        if (!CANONICAL_UUID.matcher(trimmed).matches()) {
            throw new InvalidInstallationIdException("X-Installation-Id no es un UUID válido");
        }
        return new InstallationId(UUID.fromString(trimmed));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
