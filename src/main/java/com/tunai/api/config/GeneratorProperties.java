package com.tunai.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Selección del proveedor de generación 3D (RF-BE-021, Anexo A). */
@Validated
@ConfigurationProperties("tunai.generator")
public record GeneratorProperties(
        @NotNull @DefaultValue("fake") Provider provider, @NotNull @Valid @DefaultValue Fake fake) {

    public enum Provider {
        FAKE,
        STABILITY
    }

    /**
     * @param sampleLocation ruta en el classpath del GLB de ejemplo
     * @param delay latencia simulada por generación
     */
    public record Fake(
            @NotNull @DefaultValue("fake/vehicle-sample.glb") String sampleLocation,
            @NotNull @DefaultValue("2s") Duration delay) {}
}
