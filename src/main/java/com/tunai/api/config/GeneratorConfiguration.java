package com.tunai.api.config;

import com.tunai.api.adapter.out.fake.FakeModelGenerator;
import com.tunai.api.adapter.out.stability.StabilityFast3dAdapter;
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

/** Ensambla el {@link ModelGeneratorPort} según {@code tunai.generator.provider} (RF-BE-021). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GeneratorProperties.class, StabilityProperties.class, GenerationProperties.class})
public class GeneratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GeneratorConfiguration.class);

    @Bean
    ModelGeneratorPort modelGenerator(
            GeneratorProperties generator, StabilityProperties stability, GenerationProperties generation) {
        log.info("Proveedor de generación 3D activo: {}", generator.provider());
        return switch (generator.provider()) {
            case FAKE -> fakeModelGenerator(generator.fake());
            case STABILITY -> stabilityModelGenerator(stability, generation);
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

    /** RNF-BE-015: con el proveedor real, la app no arranca si falta la clave. */
    private static ModelGeneratorPort stabilityModelGenerator(
            StabilityProperties stability, GenerationProperties generation) {
        if (!stability.hasApiKey()) {
            throw new IllegalStateException(
                    "El proveedor 'stability' requiere la variable de entorno STABILITY_API_KEY");
        }
        stability.generationOptions(); // valida los parámetros de SF3D al arrancar
        log.warn(
                "Generador STABILITY: cada generación exitosa gasta créditos reales. Endpoint {}, timeouts {}/{}, {}",
                stability.baseUrl(),
                stability.connectTimeout(),
                stability.readTimeout(),
                stability.generationOptions());
        return StabilityFast3dAdapter.create(
                stability.baseUrl(),
                stability.apiKey(),
                stability.connectTimeout(),
                stability.readTimeout(),
                generation.maxModelBytes().toBytes(),
                stability.vertexCount());
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
