package com.tunai.api.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.VehicleImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MeteredModelGeneratorTest {

    private static final VehicleImage IMAGE = new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, 512);
    private static final byte[] GLB = "glTF\u0002\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double generations(String result) {
        var counter = registry.find(MeteredModelGenerator.GENERATIONS)
                .tags("provider", "stability", "result", result)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void countsAndTimesASuccess() {
        ModelGeneratorPort ok = (image, options) -> GeneratedModel.fromGlb(GLB);

        new MeteredModelGenerator(ok, registry, "STABILITY").generate(IMAGE, GenerationOptions.DEFAULTS);

        assertThat(generations("success")).isEqualTo(1);
        assertThat(registry.get(MeteredModelGenerator.LATENCY)
                        .tags("provider", "stability", "result", "success")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of((Supplier<RuntimeException>) () -> new ProviderRejectedException("x"), "rejected"),
                Arguments.of((Supplier<RuntimeException>) () -> new ProviderTimeoutException("x", null), "timeout"),
                Arguments.of((Supplier<RuntimeException>) () -> new ProviderUnavailableException("x"), "unavailable"),
                Arguments.of((Supplier<RuntimeException>) () -> new ProviderFailureException("x"), "error"),
                Arguments.of((Supplier<RuntimeException>) () -> new IllegalStateException("x"), "error"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failures")
    void classifiesFailuresAndRethrows(Supplier<RuntimeException> failure, String result) {
        RuntimeException thrown = failure.get();
        ModelGeneratorPort failing = (image, options) -> {
            throw thrown;
        };

        assertThatThrownBy(() -> new MeteredModelGenerator(failing, registry, "stability")
                        .generate(IMAGE, GenerationOptions.DEFAULTS))
                .isSameAs(thrown);
        assertThat(generations(result)).isEqualTo(1);
        assertThat(generations("success")).isZero();
    }
}
