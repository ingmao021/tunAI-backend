package com.tunai.api.adapter.out.imageio;

import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.model.ImageDimensionsReader;
import com.tunai.api.domain.model.ImageFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Lee las dimensiones desde la cabecera con ImageIO, sin decodificar los píxeles (RF-BE-006). Usa un stream con caché
 * en memoria: {@code ImageIO.createImageInputStream} puede escribir un archivo temporal, y RF-BE-018 lo prohíbe.
 */
public final class ImageIoDimensionsReader implements ImageDimensionsReader {

    @Override
    public Dimensions read(byte[] data, ImageFormat format) {
        ImageReader reader = readerFor(format);
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            return new Dimensions(reader.getWidth(0), reader.getHeight(0));
        } catch (IOException | RuntimeException e) {
            throw new InvalidImageException("No se pudo leer la imagen", e);
        } finally {
            reader.dispose();
        }
    }

    private static ImageReader readerFor(ImageFormat format) {
        String formatName =
                switch (format) {
                    case JPEG -> "jpeg";
                    case PNG -> "png";
                };
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("La JVM no tiene lector ImageIO para " + formatName);
        }
        return readers.next();
    }
}
