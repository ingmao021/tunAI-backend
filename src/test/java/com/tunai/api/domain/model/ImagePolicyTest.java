package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.TestImages;
import com.tunai.api.domain.exception.ImageTooLargeException;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.exception.UnsupportedImageFormatException;
import com.tunai.api.domain.model.ImageDimensionsReader.Dimensions;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ImagePolicyTest {

    private final ImagePolicy policy = new ImagePolicy(10_000, 512);

    @Test
    void acceptsAnImageThatMeetsEveryRule() {
        VehicleImage image = policy.toVehicleImage(TestImages.png(1, 1), (data, format) -> new Dimensions(800, 512));

        assertThat(image.format()).isEqualTo(ImageFormat.PNG);
        assertThat(image.width()).isEqualTo(800);
        assertThat(image.height()).isEqualTo(512);
    }

    @Test
    void rejectsAShortestSideBelowTheMinimum() {
        assertThatThrownBy(
                        () -> policy.toVehicleImage(TestImages.jpeg(1, 1), (data, format) -> new Dimensions(1024, 511)))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("511");
    }

    @Test
    void checksSizeBeforeReadingTheImage() {
        AtomicBoolean readerCalled = new AtomicBoolean();
        byte[] tooLarge = new byte[10_001];

        assertThatThrownBy(() -> policy.toVehicleImage(tooLarge, (data, format) -> {
                    readerCalled.set(true);
                    return new Dimensions(512, 512);
                }))
                .isInstanceOf(ImageTooLargeException.class);
        assertThat(readerCalled).isFalse();
    }

    @Test
    void checksTheSignatureBeforeReadingDimensions() {
        AtomicBoolean readerCalled = new AtomicBoolean();

        assertThatThrownBy(() -> policy.toVehicleImage(TestImages.webpHeader(), (data, format) -> {
                    readerCalled.set(true);
                    return new Dimensions(512, 512);
                }))
                .isInstanceOf(UnsupportedImageFormatException.class);
        assertThat(readerCalled).isFalse();
    }

    @Test
    void rejectsEmptyData() {
        assertThatThrownBy(() -> policy.toVehicleImage(new byte[0], (data, format) -> new Dimensions(512, 512)))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ImagePolicy(0, 512)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImagePolicy(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
