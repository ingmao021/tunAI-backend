package com.tunai.api.domain.model;

import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.exception.UnsupportedImageFormatException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Detecta el formato de una imagen por su firma de bytes, sin confiar en el {@code Content-Type} ni en la extensión
 * (RF-BE-005).
 */
public final class ImageSignature {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] GIF = ascii("GIF8");
    private static final byte[] BMP = ascii("BM");
    private static final byte[] TIFF_LE = {'I', 'I', 0x2A, 0x00};
    private static final byte[] TIFF_BE = {'M', 'M', 0x00, 0x2A};
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP = ascii("WEBP");
    private static final byte[] FTYP = ascii("ftyp");
    private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif");

    private ImageSignature() {}

    /**
     * @throws UnsupportedImageFormatException si es una imagen reconocible de otro formato (415)
     * @throws InvalidImageException si los bytes no corresponden a ninguna imagen reconocible (400)
     */
    public static ImageFormat detect(byte[] data) {
        if (startsWith(data, 0, JPEG)) {
            return ImageFormat.JPEG;
        }
        if (startsWith(data, 0, PNG)) {
            return ImageFormat.PNG;
        }
        String other = recognizeUnsupported(data);
        if (other != null) {
            throw new UnsupportedImageFormatException("Formato " + other + " no soportado; usa JPEG o PNG");
        }
        throw new InvalidImageException("El archivo no es una imagen reconocible");
    }

    private static String recognizeUnsupported(byte[] data) {
        if (startsWith(data, 0, RIFF) && startsWith(data, 8, WEBP)) {
            return "WEBP";
        }
        if (startsWith(data, 4, FTYP) && data.length >= 12) {
            String brand = new String(data, 8, 4, StandardCharsets.US_ASCII);
            if (HEIF_BRANDS.contains(brand)) {
                return brand.equals("avif") ? "AVIF" : "HEIC";
            }
        }
        if (startsWith(data, 0, GIF)) {
            return "GIF";
        }
        if (startsWith(data, 0, TIFF_LE) || startsWith(data, 0, TIFF_BE)) {
            return "TIFF";
        }
        if (startsWith(data, 0, BMP)) {
            return "BMP";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
