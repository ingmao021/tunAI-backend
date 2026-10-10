package com.tunai.api.domain.model;

import com.tunai.api.domain.exception.ImageTooLargeException;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.model.ImageDimensionsReader.Dimensions;
import java.util.Objects;

/**
 * Reglas de aceptación de la foto (RN-04): solo JPEG o PNG, tamaño máximo y lado menor mínimo. Los límites vienen de
 * la configuración (Anexo A).
 */
public record ImagePolicy(long maxBytes, int minSide) {

    public ImagePolicy {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes debe ser positivo: " + maxBytes);
        }
        if (minSide <= 0) {
            throw new IllegalArgumentException("minSide debe ser positivo: " + minSide);
        }
    }

    /**
     * Valida la foto en orden de costo creciente: tamaño, firma de bytes y dimensiones.
     *
     * @throws ImageTooLargeException si supera {@code maxBytes} (413)
     * @throws com.tunai.api.domain.exception.UnsupportedImageFormatException si es otro formato de imagen (415)
     * @throws InvalidImageException si no es una imagen, no se puede leer o su lado menor es muy pequeño (400)
     */
    public VehicleImage toVehicleImage(byte[] data, ImageDimensionsReader dimensionsReader) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(dimensionsReader, "dimensionsReader");
        if (data.length == 0) {
            throw new InvalidImageException("La imagen está vacía");
        }
        if (data.length > maxBytes) {
            throw new ImageTooLargeException("La imagen supera el máximo de " + maxBytes + " bytes");
        }
        ImageFormat format = ImageSignature.detect(data);
        Dimensions dimensions = dimensionsReader.read(data, format);
        int shortestSide = Math.min(dimensions.width(), dimensions.height());
        if (shortestSide < minSide) {
            throw new InvalidImageException(
                    "El lado menor de la imagen es " + shortestSide + " px; el mínimo es " + minSide + " px");
        }
        return new VehicleImage(data, format, dimensions.width(), dimensions.height());
    }
}
