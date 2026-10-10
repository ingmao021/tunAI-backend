package com.tunai.api.domain.model;

import com.tunai.api.domain.exception.InvalidImageException;

/** Lee el ancho y el alto de una imagen desde su cabecera, sin decodificarla completa (RF-BE-006). */
@FunctionalInterface
public interface ImageDimensionsReader {

    /**
     * @throws InvalidImageException si la cabecera no se puede leer
     */
    Dimensions read(byte[] data, ImageFormat format);

    record Dimensions(int width, int height) {}
}
