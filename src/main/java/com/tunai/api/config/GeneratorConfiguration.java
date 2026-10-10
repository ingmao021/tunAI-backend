package com.tunai.api.config;

import com.tunai.api.adapter.out.fake.FakeModelGenerator;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** Ensambla el {@link ModelGeneratorPort} según {@code tunai.generator.provider}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeneratorProperties.class)
public class GeneratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GeneratorConfiguration.class);

    @Bean
    ModelGeneratorPort modelGenerator(GeneratorProperties properties) {
        log.info("Proveedor de generación 3D activo: {}", properties.provider());
        return switch (properties.provider()) {
            case FAKE -> fakeModelGenerator(properties.fake());
            case STABILITY ->
                throw new IllegalStateException(
                        "El proveedor 'stability' aún no está implementado (Fase 4). Usa TUNAI_GENERATOR_PROVIDER=fake.");
        };
    }

    private static ModelGeneratorPort fakeModelGenerator(GeneratorProperties.Fake fake) {
        byte[] sample = readClasspath(fake.sampleLocation());
        log.info(
                "Generador fake: devuelve '{}' ({} bytes) con {} de latencia simulada",
                fake.sampleLocation(),
                sample.length,
                fake.delay());
        return new FakeModelGenerator(sample, fake.delay());
    }

    private static byte[] readClasspath(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el GLB de ejemplo: classpath:" + location, e);
        }
    }
}
