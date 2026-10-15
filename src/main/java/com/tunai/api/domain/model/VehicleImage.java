package com.tunai.api.domain.model;

import java.util.Objects;

/**
 * Foto del vehículo ya leída en memoria. Las reglas de RN-04 (firma de bytes, tamaño y lado mínimo) las aplica
 * {@link ImagePolicy} antes de construirla; aquí solo se garantizan las invariantes estructurales.
 *
 * <p>El constructor no copia el arreglo: la foto puede pesar 10 MB y quien la construye ya tiene su propia copia (la
 * del multipart), así que le cede el arreglo y no debe modificarlo después. Lo que se expone sí es una copia.
 */
public final class VehicleImage {

    private final byte[] data;
    private final ImageFormat format;
    private final int width;
    private final int height;

    public VehicleImage(byte[] data, ImageFormat format, int width, int height) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(format, "format");
        if (data.length == 0) {
            throw new IllegalArgumentException("La imagen está vacía");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensiones inválidas: " + width + "x" + height);
        }
        this.data = data;
        this.format = format;
        this.width = width;
        this.height = height;
    }

    public byte[] data() {
        return data.clone();
    }

    public int sizeInBytes() {
        return data.length;
    }

    public ImageFormat format() {
        return format;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
