package com.tunai.api.domain.model;

import java.util.Objects;
import java.util.Set;

/** Parámetros de generación de SF3D (RF-BE-012). */
public record GenerationOptions(int textureResolution, double foregroundRatio, Remesh remesh) {

    private static final Set<Integer> TEXTURE_RESOLUTIONS = Set.of(512, 1024, 2048);

    public static final GenerationOptions DEFAULTS = new GenerationOptions(1024, 0.85, Remesh.NONE);

    public GenerationOptions {
        Objects.requireNonNull(remesh, "remesh");
        if (!TEXTURE_RESOLUTIONS.contains(textureResolution)) {
            throw new IllegalArgumentException("textureResolution debe ser 512, 1024 o 2048: " + textureResolution);
        }
        if (foregroundRatio < 0.1 || foregroundRatio > 1.0) {
            throw new IllegalArgumentException("foregroundRatio debe estar entre 0.1 y 1.0: " + foregroundRatio);
        }
    }

    public enum Remesh {
        NONE,
        QUAD,
        TRIANGLE
    }
}
