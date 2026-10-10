package com.tunai.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Imágenes reales generadas en memoria para las pruebas. */
public final class TestImages {

    private TestImages() {}

    public static byte[] png(int width, int height) {
        return encode("png", width, height);
    }

    public static byte[] jpeg(int width, int height) {
        return encode("jpeg", width, height);
    }

    /** Cabecera RIFF/WEBP mínima: suficiente para la detección por firma. */
    public static byte[] webpHeader() {
        return "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] encode(String format, int width, int height) {
        ImageIO.setUseCache(false);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("Sin escritor ImageIO para " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
