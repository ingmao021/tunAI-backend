package com.tunai.api.adapter.out.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.VehicleImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeModelGeneratorTest {

    private static final byte[] SAMPLE = "glTF\u0002\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final VehicleImage IMAGE = new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, 512);

    @Test
    void returnsTheSampleGlb() {
        FakeModelGenerator generator = new FakeModelGenerator(SAMPLE, Duration.ZERO);

        assertThat(generator.generate(IMAGE, GenerationOptions.DEFAULTS).toByteArray())
                .isEqualTo(SAMPLE);
    }

    @Test
    void simulatesLatency() {
        FakeModelGenerator generator = new FakeModelGenerator(SAMPLE, Duration.ofMillis(50));

        long start = System.nanoTime();
        generator.generate(IMAGE, GenerationOptions.DEFAULTS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void interruptionFailsAsProviderFailureAndKeepsTheInterruptFlag() {
        FakeModelGenerator generator = new FakeModelGenerator(SAMPLE, Duration.ofSeconds(10));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> generator.generate(IMAGE, GenerationOptions.DEFAULTS))
                    .isInstanceOf(ProviderFailureException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failsFastWhenTheSampleIsNotAGlb() {
        byte[] notGlb = "not a model".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new FakeModelGenerator(notGlb, Duration.ZERO))
                .isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void rejectsNegativeDelay() {
        assertThatThrownBy(() -> new FakeModelGenerator(SAMPLE, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
