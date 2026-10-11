package com.tunai.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.fake.FakeModelGenerator;
import com.tunai.api.adapter.out.stability.StabilityFast3dAdapter;
import com.tunai.api.adapter.out.tripo.TripoAdapter;
import com.tunai.api.adapter.out.tripo.TripoOptions;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeneratorConfigurationTest {

    private static final String KEY = "sk-secret-abcdef";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GeneratorConfiguration.class);

    @Test
    void usesFakeWhenNothingIsConfigured() {
        runner.run(context ->
                assertThat(context).getBean(ModelGeneratorPort.class).isInstanceOf(FakeModelGenerator.class));
    }

    @Test
    void usesStabilityWhenConfiguredWithAKey() {
        runner.withPropertyValues("tunai.generator.provider=stability", "tunai.stability.api-key=" + KEY)
                .run(context -> assertThat(context)
                        .getBean(ModelGeneratorPort.class)
                        .isInstanceOf(StabilityFast3dAdapter.class));
    }

    /** RNF-BE-015: sin clave no arranca, y el mensaje no expone ningún secreto. */
    @Test
    void refusesToStartWithStabilityWithoutAKey() {
        runner.withPropertyValues("tunai.generator.provider=stability").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("STABILITY_API_KEY"));
    }

    @Test
    void refusesToStartWithInvalidStabilityOptions() {
        runner.withPropertyValues(
                        "tunai.generator.provider=stability",
                        "tunai.stability.api-key=" + KEY,
                        "tunai.stability.texture-resolution=999")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void usesTripoWhenConfiguredWithAKey() {
        runner.withPropertyValues("tunai.generator.provider=tripo", "tunai.tripo.api-key=" + KEY)
                .run(context ->
                        assertThat(context).getBean(ModelGeneratorPort.class).isInstanceOf(TripoAdapter.class));
    }

    /** RNF-BE-015: sin clave no arranca, y el mensaje no expone ningún secreto. */
    @Test
    void refusesToStartWithTripoWithoutAKey() {
        runner.withPropertyValues("tunai.generator.provider=tripo")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("TRIPO_API_KEY"));
    }

    /** Sin límite de caras o fuera de rango, el GLB podría superar el máximo o ser rechazado por Tripo. */
    @Test
    void refusesToStartWithAnInvalidTripoFaceLimit() {
        runner.withPropertyValues(
                        "tunai.generator.provider=tripo", "tunai.tripo.api-key=" + KEY, "tunai.tripo.face-limit=100")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void tripoTextureQualityAcceptsLowercase() {
        runner.withPropertyValues("tunai.tripo.texture-quality=detailed").run(context -> assertThat(
                        context.getBean(TripoProperties.class).options().textureQuality())
                .isEqualTo(TripoOptions.TextureQuality.DETAILED));
    }

    @Test
    void failsToStartWhenTheSampleIsMissing() {
        runner.withPropertyValues("tunai.generator.fake.sample-location=fake/does-not-exist.glb")
                .run(context -> assertThat(context).hasFailed());
    }

    /** RNF-BE-006: la clave no aparece en el toString de las propiedades. */
    @Test
    void stabilityPropertiesNeverPrintTheKey() {
        runner.withPropertyValues("tunai.stability.api-key=" + KEY).run(context -> {
            StabilityProperties properties = context.getBean(StabilityProperties.class);

            assertThat(properties.toString()).doesNotContain(KEY).contains("apiKey=****");
        });
    }

    @Test
    void tripoPropertiesNeverPrintTheKey() {
        runner.withPropertyValues("tunai.tripo.api-key=" + KEY).run(context -> {
            TripoProperties properties = context.getBean(TripoProperties.class);

            assertThat(properties.toString()).doesNotContain(KEY).contains("apiKey=****");
        });
    }
}
