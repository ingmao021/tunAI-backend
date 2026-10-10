package com.tunai.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.fake.FakeModelGenerator;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GeneratorConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GeneratorConfiguration.class);

    @Test
    void usesFakeWhenNothingIsConfigured() {
        runner.run(context ->
                assertThat(context).getBean(ModelGeneratorPort.class).isInstanceOf(FakeModelGenerator.class));
    }

    @Test
    void refusesToStartWithStabilityUntilItIsImplemented() {
        runner.withPropertyValues("tunai.generator.provider=stability")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("Fase 4"));
    }

    @Test
    void failsToStartWhenTheSampleIsMissing() {
        runner.withPropertyValues("tunai.generator.fake.sample-location=fake/does-not-exist.glb")
                .run(context -> assertThat(context).hasFailed());
    }
}
