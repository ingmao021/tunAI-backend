package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VehicleImageTest {

    @Test
    void keepsItsValues() {
        VehicleImage image = new VehicleImage(new byte[] {1, 2, 3}, ImageFormat.PNG, 640, 480);

        assertThat(image.sizeInBytes()).isEqualTo(3);
        assertThat(image.format()).isEqualTo(ImageFormat.PNG);
        assertThat(image.width()).isEqualTo(640);
        assertThat(image.height()).isEqualTo(480);
    }

    @Test
    void isImmutable() {
        byte[] source = {1, 2, 3};
        VehicleImage image = new VehicleImage(source, ImageFormat.JPEG, 512, 512);

        source[0] = 9;
        image.data()[1] = 9;

        assertThat(image.data()).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsEmptyData() {
        assertThatThrownBy(() -> new VehicleImage(new byte[0], ImageFormat.JPEG, 512, 512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveDimensions() {
        assertThatThrownBy(() -> new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 0, 512))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
