package com.tunai.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BeanConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GeneratorConfiguration.class, ClockConfiguration.class, BeanConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void multipartLimitsDeriveFromTheImageLimit() {
        runner.withPropertyValues("tunai.image.max-bytes=10MB").run(context -> {
            MultipartConfigElement multipart = context.getBean(MultipartConfigElement.class);

            assertThat(multipart.getMaxFileSize()).isEqualTo(10L * 1024 * 1024);
            assertThat(multipart.getMaxRequestSize()).isEqualTo(11L * 1024 * 1024);
        });
    }

    /** CA-BE-10 / RF-BE-018: todo lo que cabe en una petición válida se queda en memoria, nunca en disco temporal. */
    @Test
    void uploadsStayInMemory() {
        runner.run(context -> {
            MultipartConfigElement multipart = context.getBean(MultipartConfigElement.class);

            assertThat((long) multipart.getFileSizeThreshold()).isGreaterThanOrEqualTo(multipart.getMaxRequestSize());
        });
    }

    @Test
    void rejectsAnInvalidImageLimitAtStartup() {
        runner.withPropertyValues("tunai.image.min-side=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
