package com.tunai.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.tripo.TripoAdapter;
import com.tunai.api.adapter.out.tripo.TripoOptions;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeneratorConfigurationTest {

    private static final String KEY = "tsk_secret-abcdef";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GeneratorConfiguration.class);

    @Test
    void usesTripoWhenConfiguredWithAKey() {
        runner.withPropertyValues("tunai.tripo.api-key=" + KEY)
                .run(context ->
                        assertThat(context).getBean(ModelGeneratorPort.class).isInstanceOf(TripoAdapter.class));
    }

    /** RNF-BE-015: sin clave no arranca, y el mensaje no expone ningún secreto. */
    @Test
    void refusesToStartWithoutAKey() {
        runner.run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("TRIPO_API_KEY"));
    }

    /** Sin límite de caras o fuera de rango, el GLB podría superar el máximo o ser rechazado por Tripo. */
    @Test
    void refusesToStartWithAnInvalidFaceLimit() {
        runner.withPropertyValues("tunai.tripo.api-key=" + KEY, "tunai.tripo.face-limit=100")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Los valores por defecto fijan el costo por modelo en 40 créditos (§13.9). */
    @Test
    void defaultsAreDetailedTextureStandardGeometryAndPbr() {
        runner.withPropertyValues("tunai.tripo.api-key=" + KEY).run(context -> assertThat(
                        context.getBean(TripoProperties.class).options())
                .isEqualTo(new TripoOptions(
                        "v3.1-20260211",
                        200_000,
                        TripoOptions.TextureQuality.DETAILED,
                        TripoOptions.GeometryQuality.STANDARD,
                        true)));
    }

    @Test
    void qualitiesAcceptLowercase() {
        runner.withPropertyValues(
                        "tunai.tripo.api-key=" + KEY,
                        "tunai.tripo.texture-quality=standard",
                        "tunai.tripo.geometry-quality=detailed")
                .run(context -> {
                    TripoOptions options =
                            context.getBean(TripoProperties.class).options();

                    assertThat(options.textureQuality()).isEqualTo(TripoOptions.TextureQuality.STANDARD);
                    assertThat(options.geometryQuality()).isEqualTo(TripoOptions.GeometryQuality.DETAILED);
                });
    }

    /** RNF-BE-006: la clave no aparece en el toString de las propiedades. */
    @Test
    void propertiesNeverPrintTheKey() {
        runner.withPropertyValues("tunai.tripo.api-key=" + KEY).run(context -> {
            TripoProperties properties = context.getBean(TripoProperties.class);

            assertThat(properties.toString()).doesNotContain(KEY).contains("apiKey=****");
        });
    }
}
