package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.exception.ImageTooLargeException;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.model.ImageDimensionsReader;
import com.tunai.api.domain.model.ImagePolicy;
import com.tunai.api.domain.model.VehicleImage;
import java.io.IOException;
import java.util.Objects;
import org.springframework.web.multipart.MultipartFile;

/** Convierte la parte multipart {@code image} en un {@link VehicleImage} validado según RN-04. */
public class ImageRequestMapper {

    private final ImagePolicy policy;
    private final ImageDimensionsReader dimensionsReader;

    public ImageRequestMapper(ImagePolicy policy, ImageDimensionsReader dimensionsReader) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.dimensionsReader = Objects.requireNonNull(dimensionsReader, "dimensionsReader");
    }

    public VehicleImage toVehicleImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("El campo 'image' está vacío");
        }
        if (file.getSize() > policy.maxBytes()) {
            throw new ImageTooLargeException("La imagen supera el máximo de " + policy.maxBytes() + " bytes");
        }
        return policy.toVehicleImage(readBytes(file), dimensionsReader);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidImageException("No se pudo leer la imagen enviada", e);
        }
    }
}
