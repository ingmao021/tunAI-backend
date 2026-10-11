package com.tunai.api.adapter.out.tripo;

import java.util.Locale;
import java.util.Objects;

/**
 * Parámetros de {@code POST /v3/generation/image-to-model} (§13.9). Se fijan por configuración y no por petición: el
 * visor del cliente necesita siempre la misma forma de GLB (1 malla con textura de color base).
 *
 * @param model versión del modelo de Tripo, por ejemplo {@code v3.1-20260211}
 * @param faceLimit máximo de caras; acota el tamaño del GLB y lo que el visor tiene que dibujar
 * @param textureQuality calidad de la textura ({@code detailed} cuesta más créditos)
 * @param pbr si se piden los mapas PBR además del color base
 */
public record TripoOptions(String model, int faceLimit, TextureQuality textureQuality, boolean pbr) {

    /** Límites de caras que acepta Tripo en modo triángulos (v3.1, calidad estándar). */
    public static final int MIN_FACES = 500;

    public static final int MAX_FACES = 1_500_000;

    public enum TextureQuality {
        STANDARD,
        DETAILED;

        String apiValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public TripoOptions {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(textureQuality, "textureQuality");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model no puede estar vacío");
        }
        if (faceLimit < MIN_FACES || faceLimit > MAX_FACES) {
            throw new IllegalArgumentException(
                    "faceLimit debe estar entre " + MIN_FACES + " y " + MAX_FACES + ": " + faceLimit);
        }
    }
}
