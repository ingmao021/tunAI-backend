package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.TestImages;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.exception.UnsupportedImageFormatException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageSignatureTest {

    @Test
    void detectsRealJpegAndPng() {
        assertThat(ImageSignature.detect(TestImages.jpeg(8, 8))).isEqualTo(ImageFormat.JPEG);
        assertThat(ImageSignature.detect(TestImages.png(8, 8))).isEqualTo(ImageFormat.PNG);
    }

    @Test
    void rejectsWebpAsUnsupported() {
        assertThatThrownBy(() -> ImageSignature.detect(TestImages.webpHeader()))
                .isInstanceOf(UnsupportedImageFormatException.class)
                .hasMessageContaining("WEBP");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GIF89a......",
                "BM..........",
                "II*\u0000........",
                "MM\u0000*........",
                "\u0000\u0000\u0000\u0018ftypheic....",
                "\u0000\u0000\u0000\u0018ftypmif1....",
                "\u0000\u0000\u0000\u0018ftypavif...."
            })
    void rejectsOtherImageFormatsAsUnsupported(String header) {
        assertThatThrownBy(() -> ImageSignature.detect(header.getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(UnsupportedImageFormatException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "%PDF-1.7 ....", "{\"json\":true}", "\u0000\u0000\u0000\u0018ftypmp42...."})
    void rejectsNonImagesAsInvalid(String content) {
        assertThatThrownBy(() -> ImageSignature.detect(content.getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void doesNotTrustATruncatedPngSignature() {
        byte[] truncated = {(byte) 0x89, 'P', 'N', 'G'};

        assertThatThrownBy(() -> ImageSignature.detect(truncated)).isInstanceOf(InvalidImageException.class);
    }
}
