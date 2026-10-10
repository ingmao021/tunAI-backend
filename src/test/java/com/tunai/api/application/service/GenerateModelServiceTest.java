package com.tunai.api.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.application.port.in.GenerateModelUseCase.GenerateCommand;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.VehicleImage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GenerateModelServiceTest {

    private static final byte[] GLB = "glTF\u0002\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final GenerateCommand COMMAND = new GenerateCommand(
            new InstallationId(UUID.randomUUID()), new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, 512));

    @Test
    void returnsTheGeneratedModelWithTheConfiguredOptions() {
        GenerationOptions options = new GenerationOptions(512, 0.9, GenerationOptions.Remesh.QUAD);
        ModelGeneratorPort generator = (image, opts) -> {
            assertThat(opts).isEqualTo(options);
            return GeneratedModel.fromGlb(GLB);
        };

        GeneratedModel model = new GenerateModelService(generator, options, 1024).generate(COMMAND);

        assertThat(model.toByteArray()).isEqualTo(GLB);
    }

    @Test
    void rejectsAModelLargerThanTheMaximum() {
        ModelGeneratorPort generator = (image, opts) -> GeneratedModel.fromGlb(GLB);
        GenerateModelService service = new GenerateModelService(generator, GenerationOptions.DEFAULTS, GLB.length - 1);

        assertThatThrownBy(() -> service.generate(COMMAND)).isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void neverRetriesTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        ModelGeneratorPort failing = (image, opts) -> {
            calls.incrementAndGet();
            throw new ProviderFailureException("5xx");
        };
        GenerateModelService service = new GenerateModelService(failing, GenerationOptions.DEFAULTS, 1024);

        assertThatThrownBy(() -> service.generate(COMMAND)).isInstanceOf(ProviderFailureException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsANonPositiveMaximum() {
        ModelGeneratorPort generator = (image, opts) -> GeneratedModel.fromGlb(GLB);

        assertThatThrownBy(() -> new GenerateModelService(generator, GenerationOptions.DEFAULTS, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
