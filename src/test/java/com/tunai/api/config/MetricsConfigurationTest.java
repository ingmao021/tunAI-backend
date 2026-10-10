package com.tunai.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MetricsConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(MetricsConfiguration.class);

    /** Solo se guardan las métricas propias; las de la JVM y Tomcat se descartan para ahorrar memoria (512 MB). */
    @Test
    void keepsOnlyTunaiMeters() {
        runner.run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            registry.counter("tunai.generations", "result", "success").increment();
            registry.counter("jvm.gc.pause").increment();

            assertThat(registry.find("tunai.generations").counter()).isNotNull();
            assertThat(registry.find("jvm.gc.pause").counter()).isNull();
        });
    }
}
