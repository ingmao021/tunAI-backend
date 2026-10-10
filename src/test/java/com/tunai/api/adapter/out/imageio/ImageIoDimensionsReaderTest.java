package com.tunai.api.adapter.out.imageio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.TestImages;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.model.ImageDimensionsReader.Dimensions;
import com.tunai.api.domain.model.ImageFormat;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ImageIoDimensionsReaderTest {

    private final ImageIoDimensionsReader reader = new ImageIoDimensionsReader();

    @Test
    void readsPngDimensions() {
        assertThat(reader.read(TestImages.png(640, 520), ImageFormat.PNG)).isEqualTo(new Dimensions(640, 520));
    }

    @Test
    void readsJpegDimensions() {
        assertThat(reader.read(TestImages.jpeg(530, 700), ImageFormat.JPEG)).isEqualTo(new Dimensions(530, 700));
    }

    @Test
    void rejectsACorruptHeader() {
        byte[] png = TestImages.png(64, 64);
        byte[] onlySignature = Arrays.copyOf(png, 10);

        assertThatThrownBy(() -> reader.read(onlySignature, ImageFormat.PNG)).isInstanceOf(InvalidImageException.class);
    }

    @Test
    void rejectsBytesThatDoNotMatchTheDeclaredFormat() {
        assertThatThrownBy(() -> reader.read(TestImages.png(64, 64), ImageFormat.JPEG))
                .isInstanceOf(InvalidImageException.class);
    }
}
